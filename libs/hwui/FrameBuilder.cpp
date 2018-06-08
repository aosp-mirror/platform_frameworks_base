/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "FrameBuilder.h"

#include "DeferredLayerUpdater.h"
#include "LayerUpdateQueue.h"
#include "RenderNode.h"
#include "VectorDrawable.h"
#include "hwui/Canvas.h"
#include "renderstate/OffscreenBufferPool.h"
#include "utils/FatVector.h"
#include "utils/PaintUtils.h"
#include "utils/TraceUtils.h"

#include <SkPathOps.h>
#include <utils/TypeHelpers.h>

namespace android {
namespace uirenderer {

FrameBuilder::FrameBuilder(const SkRect& clip, uint32_t viewportWidth, uint32_t viewportHeight,
                           const LightGeometry& lightGeometry, Caches& caches)
        : mStdAllocator(mAllocator)
        , mLayerBuilders(mStdAllocator)
        , mLayerStack(mStdAllocator)
        , mCanvasState(*this)
        , mCaches(caches)
        , mLightRadius(lightGeometry.radius)
        , mDrawFbo0(true) {
    // Prepare to defer Fbo0
    auto fbo0 = mAllocator.create<LayerBuilder>(viewportWidth, viewportHeight, Rect(clip));
    mLayerBuilders.push_back(fbo0);
    mLayerStack.push_back(0);
    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight, clip.fLeft, clip.fTop,
                                     clip.fRight, clip.fBottom, lightGeometry.center);
}

FrameBuilder::FrameBuilder(const LayerUpdateQueue& layers, const LightGeometry& lightGeometry,
                           Caches& caches)
        : mStdAllocator(mAllocator)
        , mLayerBuilders(mStdAllocator)
        , mLayerStack(mStdAllocator)
        , mCanvasState(*this)
        , mCaches(caches)
        , mLightRadius(lightGeometry.radius)
        , mDrawFbo0(false) {
    // TODO: remove, with each layer on its own save stack

    // Prepare to defer Fbo0 (which will be empty)
    auto fbo0 = mAllocator.create<LayerBuilder>(1, 1, Rect(1, 1));
    mLayerBuilders.push_back(fbo0);
    mLayerStack.push_back(0);
    mCanvasState.initializeSaveStack(1, 1, 0, 0, 1, 1, lightGeometry.center);

    deferLayers(layers);
}

void FrameBuilder::deferLayers(const LayerUpdateQueue& layers) {
    // Render all layers to be updated, in order. Defer in reverse order, so that they'll be
    // updated in the order they're passed in (mLayerBuilders are issued to Renderer in reverse)
    for (int i = layers.entries().size() - 1; i >= 0; i--) {
        RenderNode* layerNode = layers.entries()[i].renderNode.get();
        // only schedule repaint if node still on layer - possible it may have been
        // removed during a dropped frame, but layers may still remain scheduled so
        // as not to lose info on what portion is damaged
        OffscreenBuffer* layer = layerNode->getLayer();
        if (CC_LIKELY(layer)) {
            ATRACE_FORMAT("Optimize HW Layer DisplayList %s %ux%u", layerNode->getName(),
                          layerNode->getWidth(), layerNode->getHeight());

            Rect layerDamage = layers.entries()[i].damage;
            // TODO: ensure layer damage can't be larger than layer
            layerDamage.doIntersect(0, 0, layer->viewportWidth, layer->viewportHeight);
            layerNode->computeOrdering();

            // map current light center into RenderNode's coordinate space
            Vector3 lightCenter = mCanvasState.currentSnapshot()->getRelativeLightCenter();
            layer->inverseTransformInWindow.mapPoint3d(lightCenter);

            saveForLayer(layerNode->getWidth(), layerNode->getHeight(), 0, 0, layerDamage,
                         lightCenter, nullptr, layerNode);

            if (layerNode->getDisplayList()) {
                deferNodeOps(*layerNode);
            }
            restoreForLayer();
        }
    }
}

void FrameBuilder::deferRenderNode(RenderNode& renderNode) {
    renderNode.computeOrdering();

    mCanvasState.save(SaveFlags::MatrixClip);
    deferNodePropsAndOps(renderNode);
    mCanvasState.restore();
}

void FrameBuilder::deferRenderNode(float tx, float ty, Rect clipRect, RenderNode& renderNode) {
    renderNode.computeOrdering();

    mCanvasState.save(SaveFlags::MatrixClip);
    mCanvasState.translate(tx, ty);
    mCanvasState.clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                          SkClipOp::kIntersect);
    deferNodePropsAndOps(renderNode);
    mCanvasState.restore();
}

static Rect nodeBounds(RenderNode& node) {
    auto& props = node.properties();
    return Rect(props.getLeft(), props.getTop(), props.getRight(), props.getBottom());
}

void FrameBuilder::deferRenderNodeScene(const std::vector<sp<RenderNode> >& nodes,
                                        const Rect& contentDrawBounds) {
    if (nodes.size() < 1) return;
    if (nodes.size() == 1) {
        if (!nodes[0]->nothingToDraw()) {
            deferRenderNode(*nodes[0]);
        }
        return;
    }
    // It there are multiple render nodes, they are laid out as follows:
    // #0 - backdrop (content + caption)
    // #1 - content (local bounds are at (0,0), will be translated and clipped to backdrop)
    // #2 - additional overlay nodes
    // Usually the backdrop cannot be seen since it will be entirely covered by the content. While
    // resizing however it might become partially visible. The following render loop will crop the
    // backdrop against the content and draw the remaining part of it. It will then draw the content
    // cropped to the backdrop (since that indicates a shrinking of the window).
    //
    // Additional nodes will be drawn on top with no particular clipping semantics.

    // Usually the contents bounds should be mContentDrawBounds - however - we will
    // move it towards the fixed edge to give it a more stable appearance (for the moment).
    // If there is no content bounds we ignore the layering as stated above and start with 2.

    // Backdrop bounds in render target space
    const Rect backdrop = nodeBounds(*nodes[0]);

    // Bounds that content will fill in render target space (note content node bounds may be bigger)
    Rect content(contentDrawBounds.getWidth(), contentDrawBounds.getHeight());
    content.translate(backdrop.left, backdrop.top);
    if (!content.contains(backdrop) && !nodes[0]->nothingToDraw()) {
        // Content doesn't entirely overlap backdrop, so fill around content (right/bottom)

        // Note: in the future, if content doesn't snap to backdrop's left/top, this may need to
        // also fill left/top. Currently, both 2up and freeform position content at the top/left of
        // the backdrop, so this isn't necessary.
        if (content.right < backdrop.right) {
            // draw backdrop to right side of content
            deferRenderNode(0, 0,
                            Rect(content.right, backdrop.top, backdrop.right, backdrop.bottom),
                            *nodes[0]);
        }
        if (content.bottom < backdrop.bottom) {
            // draw backdrop to bottom of content
            // Note: bottom fill uses content left/right, to avoid overdrawing left/right fill
            deferRenderNode(0, 0,
                            Rect(content.left, content.bottom, content.right, backdrop.bottom),
                            *nodes[0]);
        }
    }

    if (!nodes[1]->nothingToDraw()) {
        if (!backdrop.isEmpty()) {
            // content node translation to catch up with backdrop
            float dx = contentDrawBounds.left - backdrop.left;
            float dy = contentDrawBounds.top - backdrop.top;

            Rect contentLocalClip = backdrop;
            contentLocalClip.translate(dx, dy);
            deferRenderNode(-dx, -dy, contentLocalClip, *nodes[1]);
        } else {
            deferRenderNode(*nodes[1]);
        }
    }

    // remaining overlay nodes, simply defer
    for (size_t index = 2; index < nodes.size(); index++) {
        if (!nodes[index]->nothingToDraw()) {
            deferRenderNode(*nodes[index]);
        }
    }
}

void FrameBuilder::onViewportInitialized() {}

void FrameBuilder::onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}

void FrameBuilder::deferNodePropsAndOps(RenderNode& node) {
    const RenderProperties& properties = node.properties();
    const Outline& outline = properties.getOutline();
    if (properties.getAlpha() <= 0 || (outline.getShouldClip() && outline.isEmpty()) ||
        properties.getScaleX() == 0 || properties.getScaleY() == 0) {
        return;  // rejected
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

    Rect saveLayerBounds;  // will be set to non-empty if saveLayer needed
    const bool isLayer = properties.effectiveLayerType() != LayerType::None;
    int clipFlags = properties.getClippingFlags();
    if (properties.getAlpha() < 1) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS;  // bounds clipping done by layer
        }
        if (CC_LIKELY(isLayer || !properties.getHasOverlappingRendering())) {
            // simply scale rendering content's alpha
            mCanvasState.scaleAlpha(properties.getAlpha());
        } else {
            // schedule saveLayer by initializing saveLayerBounds
            saveLayerBounds.set(0, 0, width, height);
            if (clipFlags) {
                properties.getClippingRectForFlags(clipFlags, &saveLayerBounds);
                clipFlags = 0;  // all clipping done by savelayer
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
                              SkClipOp::kIntersect);
    }

    if (properties.getRevealClip().willClip()) {
        Rect bounds;
        properties.getRevealClip().getBounds(&bounds);
        mCanvasState.setClippingRoundRect(mAllocator, bounds,
                                          properties.getRevealClip().getRadius());
    } else if (properties.getOutline().willClip()) {
        mCanvasState.setClippingOutline(mAllocator, &(properties.getOutline()));
    }

    bool quickRejected = mCanvasState.currentSnapshot()->getRenderTargetClip().isEmpty() ||
                         (properties.getClipToBounds() &&
                          mCanvasState.quickRejectConservative(0, 0, width, height));
    if (!quickRejected) {
        // not rejected, so defer render as either Layer, or direct (possibly wrapped in saveLayer)
        if (node.getLayer()) {
            // HW layer
            LayerOp* drawLayerOp = mAllocator.create_trivial<LayerOp>(node);
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
            deferBeginLayerOp(*mAllocator.create_trivial<BeginLayerOp>(
                    saveLayerBounds, Matrix4::identity(),
                    nullptr,  // no record-time clip - need only respect defer-time one
                    &saveLayerPaint));
            deferNodeOps(node);
            deferEndLayerOp(*mAllocator.create_trivial<EndLayerOp>());
        } else {
            deferNodeOps(node);
        }
    }
}

typedef key_value_pair_t<float, const RenderNodeOp*> ZRenderNodeOpPair;

template <typename V>
static void buildZSortedChildList(V* zTranslatedNodes, const DisplayList& displayList,
                                  const DisplayList::Chunk& chunk) {
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
void FrameBuilder::defer3dChildren(const ClipBase* reorderClip, ChildrenSelectMode mode,
                                   const V& zTranslatedNodes) {
    const int size = zTranslatedNodes.size();
    if (size == 0 || (mode == ChildrenSelectMode::Negative && zTranslatedNodes[0].key > 0.0f) ||
        (mode == ChildrenSelectMode::Positive && zTranslatedNodes[size - 1].key < 0.0f)) {
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
        shadowIndex = endIndex;  // draw no shadows
    } else {
        drawIndex = nonNegativeIndex;
        endIndex = size;
        shadowIndex = drawIndex;  // potentially draw shadow for each pos Z child
    }

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            const RenderNodeOp* casterNodeOp = zTranslatedNodes[shadowIndex].value;
            const float casterZ = zTranslatedNodes[shadowIndex].key;
            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < 0.1f) {
                deferShadow(reorderClip, *casterNodeOp);

                lastCasterZ = casterZ;  // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        const RenderNodeOp* childOp = zTranslatedNodes[drawIndex].value;
        deferRenderNodeOpImpl(*childOp);
        drawIndex++;
    }
}

void FrameBuilder::deferShadow(const ClipBase* reorderClip, const RenderNodeOp& casterNodeOp) {
    auto& node = *casterNodeOp.renderNode;
    auto& properties = node.properties();

    if (properties.getAlpha() <= 0.0f || properties.getOutline().getAlpha() <= 0.0f ||
        !properties.getOutline().getPath() || properties.getScaleX() == 0 ||
        properties.getScaleY() == 0) {
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
        clipBoundsPath.addRect(clipBounds.left, clipBounds.top, clipBounds.right,
                               clipBounds.bottom);

        Op(*casterPath, clipBoundsPath, kIntersect_SkPathOp, frameAllocatedPath);
        casterPath = frameAllocatedPath;
    }

    // apply reorder clip to shadow, so it respects clip at beginning of reorderable chunk
    int restoreTo = mCanvasState.save(SaveFlags::MatrixClip);
    mCanvasState.writableSnapshot()->applyClip(reorderClip,
                                               *mCanvasState.currentSnapshot()->transform);
    if (CC_LIKELY(!mCanvasState.getRenderTargetClipBounds().isEmpty())) {
        Matrix4 shadowMatrixXY(casterNodeOp.localMatrix);
        Matrix4 shadowMatrixZ(casterNodeOp.localMatrix);
        node.applyViewPropertyTransforms(shadowMatrixXY, false);
        node.applyViewPropertyTransforms(shadowMatrixZ, true);

        sp<TessellationCache::ShadowTask> task = mCaches.tessellationCache.getShadowTask(
                mCanvasState.currentTransform(), mCanvasState.getLocalClipBounds(),
                casterAlpha >= 1.0f, casterPath, &shadowMatrixXY, &shadowMatrixZ,
                mCanvasState.currentSnapshot()->getRelativeLightCenter(), mLightRadius);
        ShadowOp* shadowOp = mAllocator.create<ShadowOp>(task, casterAlpha);
        BakedOpState* bakedOpState = BakedOpState::tryShadowOpConstruct(
                mAllocator, *mCanvasState.writableSnapshot(), shadowOp);
        if (CC_LIKELY(bakedOpState)) {
            currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Shadow);
        }
    }
    mCanvasState.restoreToCount(restoreTo);
}

void FrameBuilder::deferProjectedChildren(const RenderNode& renderNode) {
    int count = mCanvasState.save(SaveFlags::MatrixClip);
    const SkPath* projectionReceiverOutline = renderNode.properties().getOutline().getPath();

    SkPath transformedMaskPath;  // on stack, since BakedOpState makes a deep copy
    if (projectionReceiverOutline) {
        // transform the mask for this projector into render target space
        // TODO: consider combining both transforms by stashing transform instead of applying
        SkMatrix skCurrentTransform;
        mCanvasState.currentTransform()->copyTo(skCurrentTransform);
        projectionReceiverOutline->transform(skCurrentTransform, &transformedMaskPath);
        mCanvasState.setProjectionPathMask(&transformedMaskPath);
    }

    for (size_t i = 0; i < renderNode.mProjectedNodes.size(); i++) {
        RenderNodeOp* childOp = renderNode.mProjectedNodes[i];
        RenderNode& childNode = *childOp->renderNode;

        // Draw child if it has content, but ignore state in childOp - matrix already applied to
        // transformFromCompositingAncestor, and record-time clip is ignored when projecting
        if (!childNode.nothingToDraw()) {
            int restoreTo = mCanvasState.save(SaveFlags::MatrixClip);

            // Apply transform between ancestor and projected descendant
            mCanvasState.concatMatrix(childOp->transformFromCompositingAncestor);

            deferNodePropsAndOps(childNode);

            mCanvasState.restoreToCount(restoreTo);
        }
    }
    mCanvasState.restoreToCount(count);
}

/**
 * Used to define a list of lambdas referencing private FrameBuilder::onXX::defer() methods.
 *
 * This allows opIds embedded in the RecordedOps to be used for dispatching to these lambdas.
 * E.g. a BitmapOp op then would be dispatched to FrameBuilder::onBitmapOp(const BitmapOp&)
 */
#define OP_RECEIVER(Type)                                       \
    [](FrameBuilder& frameBuilder, const RecordedOp& op) {      \
        frameBuilder.defer##Type(static_cast<const Type&>(op)); \
    },
void FrameBuilder::deferNodeOps(const RenderNode& renderNode) {
    typedef void (*OpDispatcher)(FrameBuilder & frameBuilder, const RecordedOp& op);
    static OpDispatcher receivers[] = BUILD_DEFERRABLE_OP_LUT(OP_RECEIVER);

    // can't be null, since DL=null node rejection happens before deferNodePropsAndOps
    const DisplayList& displayList = *(renderNode.getDisplayList());
    for (auto& chunk : displayList.getChunks()) {
        FatVector<ZRenderNodeOpPair, 16> zTranslatedNodes;
        buildZSortedChildList(&zTranslatedNodes, displayList, chunk);

        defer3dChildren(chunk.reorderClip, ChildrenSelectMode::Negative, zTranslatedNodes);
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            const RecordedOp* op = displayList.getOps()[opIndex];
            receivers[op->opId](*this, *op);

            if (CC_UNLIKELY(!renderNode.mProjectedNodes.empty() &&
                            displayList.projectionReceiveIndex >= 0 &&
                            static_cast<int>(opIndex) == displayList.projectionReceiveIndex)) {
                deferProjectedChildren(renderNode);
            }
        }
        defer3dChildren(chunk.reorderClip, ChildrenSelectMode::Positive, zTranslatedNodes);
    }
}

void FrameBuilder::deferRenderNodeOpImpl(const RenderNodeOp& op) {
    if (op.renderNode->nothingToDraw()) return;
    int count = mCanvasState.save(SaveFlags::MatrixClip);

    // apply state from RecordedOp (clip first, since op's clip is transformed by current matrix)
    mCanvasState.writableSnapshot()->applyClip(op.localClip,
                                               *mCanvasState.currentSnapshot()->transform);
    mCanvasState.concatMatrix(op.localMatrix);

    // then apply state from node properties, and defer ops
    deferNodePropsAndOps(*op.renderNode);

    mCanvasState.restoreToCount(count);
}

void FrameBuilder::deferRenderNodeOp(const RenderNodeOp& op) {
    if (!op.skipInOrderDraw) {
        deferRenderNodeOpImpl(op);
    }
}

/**
 * Defers an unmergeable, strokeable op, accounting correctly
 * for paint's style on the bounds being computed.
 */
BakedOpState* FrameBuilder::deferStrokeableOp(const RecordedOp& op, batchid_t batchId,
                                              BakedOpState::StrokeBehavior strokeBehavior,
                                              bool expandForPathTexture) {
    // Note: here we account for stroke when baking the op
    BakedOpState* bakedState = BakedOpState::tryStrokeableOpConstruct(
            mAllocator, *mCanvasState.writableSnapshot(), op, strokeBehavior, expandForPathTexture);
    if (!bakedState) return nullptr;  // quick rejected

    if (op.opId == RecordedOpId::RectOp && op.paint->getStyle() != SkPaint::kStroke_Style) {
        bakedState->setupOpacity(op.paint);
    }

    currentLayer().deferUnmergeableOp(mAllocator, bakedState, batchId);
    return bakedState;
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

void FrameBuilder::deferArcOp(const ArcOp& op) {
    // Pass true below since arcs have a tendency to draw outside their expected bounds within
    // their path textures. Passing true makes it more likely that we'll scissor, instead of
    // corrupting the frame by drawing outside of clip bounds.
    deferStrokeableOp(op, tessBatchId(op), BakedOpState::StrokeBehavior::StyleDefined, true);
}

static bool hasMergeableClip(const BakedOpState& state) {
    return !state.computedState.clipState ||
           state.computedState.clipState->mode == ClipMode::Rectangle;
}

void FrameBuilder::deferBitmapOp(const BitmapOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return;  // quick rejected

    if (op.bitmap->isOpaque()) {
        bakedState->setupOpacity(op.paint);
    }

    // Don't merge non-simply transformed or neg scale ops, SET_TEXTURE doesn't handle rotation
    // Don't merge A8 bitmaps - the paint's color isn't compared by mergeId, or in
    // MergingDrawBatch::canMergeWith()
    if (bakedState->computedState.transform.isSimple() &&
        bakedState->computedState.transform.positiveScale() &&
        PaintUtils::getBlendModeDirect(op.paint) == SkBlendMode::kSrcOver &&
        op.bitmap->colorType() != kAlpha_8_SkColorType && hasMergeableClip(*bakedState)) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.bitmap->getGenerationID());
        currentLayer().deferMergeableOp(mAllocator, bakedState, OpBatchType::Bitmap, mergeId);
    } else {
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
    }
}

void FrameBuilder::deferBitmapMeshOp(const BitmapMeshOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
}

void FrameBuilder::deferBitmapRectOp(const BitmapRectOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
}

void FrameBuilder::deferVectorDrawableOp(const VectorDrawableOp& op) {
    Bitmap& bitmap = op.vectorDrawable->getBitmapUpdateIfDirty();
    SkPaint* paint = op.vectorDrawable->getPaint();
    const BitmapRectOp* resolvedOp = mAllocator.create_trivial<BitmapRectOp>(
            op.unmappedBounds, op.localMatrix, op.localClip, paint, &bitmap,
            Rect(bitmap.width(), bitmap.height()));
    deferBitmapRectOp(*resolvedOp);
}

void FrameBuilder::deferCirclePropsOp(const CirclePropsOp& op) {
    // allocate a temporary oval op (with mAllocator, so it persists until render), so the
    // renderer doesn't have to handle the RoundRectPropsOp type, and so state baking is simple.
    float x = *(op.x);
    float y = *(op.y);
    float radius = *(op.radius);
    Rect unmappedBounds(x - radius, y - radius, x + radius, y + radius);
    const OvalOp* resolvedOp = mAllocator.create_trivial<OvalOp>(unmappedBounds, op.localMatrix,
                                                                 op.localClip, op.paint);
    deferOvalOp(*resolvedOp);
}

void FrameBuilder::deferColorOp(const ColorOp& op) {
    BakedOpState* bakedState = tryBakeUnboundedOpState(op);
    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Vertices);
}

void FrameBuilder::deferFunctorOp(const FunctorOp& op) {
    BakedOpState* bakedState = tryBakeUnboundedOpState(op);
    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Functor);
}

void FrameBuilder::deferLinesOp(const LinesOp& op) {
    batchid_t batch = op.paint->isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices;
    deferStrokeableOp(op, batch, BakedOpState::StrokeBehavior::Forced);
}

void FrameBuilder::deferOvalOp(const OvalOp& op) {
    deferStrokeableOp(op, tessBatchId(op));
}

void FrameBuilder::deferPatchOp(const PatchOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return;  // quick rejected

    if (bakedState->computedState.transform.isPureTranslate() &&
        PaintUtils::getBlendModeDirect(op.paint) == SkBlendMode::kSrcOver &&
        hasMergeableClip(*bakedState)) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.bitmap->getGenerationID());

        // Only use the MergedPatch batchId when merged, so Bitmap+Patch don't try to merge together
        currentLayer().deferMergeableOp(mAllocator, bakedState, OpBatchType::MergedPatch, mergeId);
    } else {
        // Use Bitmap batchId since Bitmap+Patch use same shader
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
    }
}

void FrameBuilder::deferPathOp(const PathOp& op) {
    auto state = deferStrokeableOp(op, OpBatchType::AlphaMaskTexture);
    if (CC_LIKELY(state)) {
        mCaches.pathCache.precache(op.path, op.paint);
    }
}

void FrameBuilder::deferPointsOp(const PointsOp& op) {
    batchid_t batch = op.paint->isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices;
    deferStrokeableOp(op, batch, BakedOpState::StrokeBehavior::Forced);
}

void FrameBuilder::deferRectOp(const RectOp& op) {
    deferStrokeableOp(op, tessBatchId(op));
}

void FrameBuilder::deferRoundRectOp(const RoundRectOp& op) {
    auto state = deferStrokeableOp(op, tessBatchId(op));
    if (CC_LIKELY(state && !op.paint->getPathEffect())) {
        // TODO: consider storing tessellation task in BakedOpState
        mCaches.tessellationCache.precacheRoundRect(state->computedState.transform, *(op.paint),
                                                    op.unmappedBounds.getWidth(),
                                                    op.unmappedBounds.getHeight(), op.rx, op.ry);
    }
}

void FrameBuilder::deferRoundRectPropsOp(const RoundRectPropsOp& op) {
    // allocate a temporary round rect op (with mAllocator, so it persists until render), so the
    // renderer doesn't have to handle the RoundRectPropsOp type, and so state baking is simple.
    const RoundRectOp* resolvedOp = mAllocator.create_trivial<RoundRectOp>(
            Rect(*(op.left), *(op.top), *(op.right), *(op.bottom)), op.localMatrix, op.localClip,
            op.paint, *op.rx, *op.ry);
    deferRoundRectOp(*resolvedOp);
}

void FrameBuilder::deferSimpleRectsOp(const SimpleRectsOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Vertices);
}

static batchid_t textBatchId(const SkPaint& paint) {
    // TODO: better handling of shader (since we won't care about color then)
    return paint.getColor() == SK_ColorBLACK ? OpBatchType::Text : OpBatchType::ColorText;
}

void FrameBuilder::deferTextOp(const TextOp& op) {
    BakedOpState* bakedState = BakedOpState::tryStrokeableOpConstruct(
            mAllocator, *mCanvasState.writableSnapshot(), op,
            BakedOpState::StrokeBehavior::StyleDefined, false);
    if (!bakedState) return;  // quick rejected

    batchid_t batchId = textBatchId(*(op.paint));
    if (bakedState->computedState.transform.isPureTranslate() &&
        PaintUtils::getBlendModeDirect(op.paint) == SkBlendMode::kSrcOver &&
        hasMergeableClip(*bakedState)) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.paint->getColor());
        currentLayer().deferMergeableOp(mAllocator, bakedState, batchId, mergeId);
    } else {
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, batchId);
    }

    FontRenderer& fontRenderer = mCaches.fontRenderer.getFontRenderer();
    auto& totalTransform = bakedState->computedState.transform;
    if (totalTransform.isPureTranslate() || totalTransform.isPerspective()) {
        fontRenderer.precache(op.paint, op.glyphs, op.glyphCount, SkMatrix::I());
    } else {
        // Partial transform case, see BakedOpDispatcher::renderTextOp
        float sx, sy;
        totalTransform.decomposeScale(sx, sy);
        fontRenderer.precache(
                op.paint, op.glyphs, op.glyphCount,
                SkMatrix::MakeScale(roundf(std::max(1.0f, sx)), roundf(std::max(1.0f, sy))));
    }
}

void FrameBuilder::deferTextOnPathOp(const TextOnPathOp& op) {
    BakedOpState* bakedState = tryBakeUnboundedOpState(op);
    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, textBatchId(*(op.paint)));

    mCaches.fontRenderer.getFontRenderer().precache(op.paint, op.glyphs, op.glyphCount,
                                                    SkMatrix::I());
}

void FrameBuilder::deferTextureLayerOp(const TextureLayerOp& op) {
    GlLayer* layer = static_cast<GlLayer*>(op.layerHandle->backingLayer());
    if (CC_UNLIKELY(!layer || !layer->isRenderable())) return;

    const TextureLayerOp* textureLayerOp = &op;
    // Now safe to access transform (which was potentially unready at record time)
    if (!layer->getTransform().isIdentity()) {
        // non-identity transform present, so 'inject it' into op by copying + replacing matrix
        Matrix4 combinedMatrix(op.localMatrix);
        combinedMatrix.multiply(layer->getTransform());
        textureLayerOp = mAllocator.create<TextureLayerOp>(op, combinedMatrix);
    }
    BakedOpState* bakedState = tryBakeOpState(*textureLayerOp);

    if (!bakedState) return;  // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::TextureLayer);
}

void FrameBuilder::saveForLayer(uint32_t layerWidth, uint32_t layerHeight, float contentTranslateX,
                                float contentTranslateY, const Rect& repaintRect,
                                const Vector3& lightCenter, const BeginLayerOp* beginLayerOp,
                                RenderNode* renderNode) {
    mCanvasState.save(SaveFlags::MatrixClip);
    mCanvasState.writableSnapshot()->initializeViewport(layerWidth, layerHeight);
    mCanvasState.writableSnapshot()->roundRectClipState = nullptr;
    mCanvasState.writableSnapshot()->setRelativeLightCenter(lightCenter);
    mCanvasState.writableSnapshot()->transform->loadTranslate(contentTranslateX, contentTranslateY,
                                                              0);
    mCanvasState.writableSnapshot()->setClip(repaintRect.left, repaintRect.top, repaintRect.right,
                                             repaintRect.bottom);

    // create a new layer repaint, and push its index on the stack
    mLayerStack.push_back(mLayerBuilders.size());
    auto newFbo = mAllocator.create<LayerBuilder>(layerWidth, layerHeight, repaintRect,
                                                  beginLayerOp, renderNode);
    mLayerBuilders.push_back(newFbo);
}

void FrameBuilder::restoreForLayer() {
    // restore canvas, and pop finished layer off of the stack
    mCanvasState.restore();
    mLayerStack.pop_back();
}

// TODO: defer time rejection (when bounds become empty) + tests
// Option - just skip layers with no bounds at playback + defer?
void FrameBuilder::deferBeginLayerOp(const BeginLayerOp& op) {
    uint32_t layerWidth = (uint32_t)op.unmappedBounds.getWidth();
    uint32_t layerHeight = (uint32_t)op.unmappedBounds.getHeight();

    auto previous = mCanvasState.currentSnapshot();
    Vector3 lightCenter = previous->getRelativeLightCenter();

    // Combine all transforms used to present saveLayer content:
    // parent content transform * canvas transform * bounds offset
    Matrix4 contentTransform(*(previous->transform));
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

    saveForLayer(layerWidth, layerHeight, contentTranslateX, contentTranslateY,
                 Rect(layerWidth, layerHeight), lightCenter, &op, nullptr);
}

void FrameBuilder::deferEndLayerOp(const EndLayerOp& /* ignored */) {
    const BeginLayerOp& beginLayerOp = *currentLayer().beginLayerOp;
    int finishedLayerIndex = mLayerStack.back();

    restoreForLayer();

    // saveLayer will clip & translate the draw contents, so we need
    // to translate the drawLayer by how much the contents was translated
    // TODO: Unify this with beginLayerOp so we don't have to calculate this
    // twice
    uint32_t layerWidth = (uint32_t)beginLayerOp.unmappedBounds.getWidth();
    uint32_t layerHeight = (uint32_t)beginLayerOp.unmappedBounds.getHeight();

    auto previous = mCanvasState.currentSnapshot();
    Vector3 lightCenter = previous->getRelativeLightCenter();

    // Combine all transforms used to present saveLayer content:
    // parent content transform * canvas transform * bounds offset
    Matrix4 contentTransform(*(previous->transform));
    contentTransform.multiply(beginLayerOp.localMatrix);
    contentTransform.translate(beginLayerOp.unmappedBounds.left, beginLayerOp.unmappedBounds.top);

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

    Matrix4 localMatrix(beginLayerOp.localMatrix);
    localMatrix.translate(saveLayerBounds.left, saveLayerBounds.top);

    // record the draw operation into the previous layer's list of draw commands
    // uses state from the associated beginLayerOp, since it has all the state needed for drawing
    LayerOp* drawLayerOp = mAllocator.create_trivial<LayerOp>(
            beginLayerOp.unmappedBounds, localMatrix, beginLayerOp.localClip, beginLayerOp.paint,
            &(mLayerBuilders[finishedLayerIndex]->offscreenBuffer));
    BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);

    if (bakedOpState) {
        // Layer will be drawn into parent layer (which is now current, since we popped mLayerStack)
        currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
    } else {
        // Layer won't be drawn - delete its drawing batches to prevent it from doing any work
        // TODO: need to prevent any render work from being done
        // - create layerop earlier for reject purposes?
        mLayerBuilders[finishedLayerIndex]->clear();
        return;
    }
}

void FrameBuilder::deferBeginUnclippedLayerOp(const BeginUnclippedLayerOp& op) {
    Matrix4 boundsTransform(*(mCanvasState.currentSnapshot()->transform));
    boundsTransform.multiply(op.localMatrix);

    Rect dstRect(op.unmappedBounds);
    boundsTransform.mapRect(dstRect);
    dstRect.roundOut();
    dstRect.doIntersect(mCanvasState.currentSnapshot()->getRenderTargetClip());

    if (dstRect.isEmpty()) {
        // Unclipped layer rejected - push a null op, so next EndUnclippedLayerOp is ignored
        currentLayer().activeUnclippedSaveLayers.push_back(nullptr);
    } else {
        // Allocate a holding position for the layer object (copyTo will produce, copyFrom will
        // consume)
        OffscreenBuffer** layerHandle = mAllocator.create<OffscreenBuffer*>(nullptr);

        /**
         * First, defer an operation to copy out the content from the rendertarget into a layer.
         */
        auto copyToOp = mAllocator.create_trivial<CopyToLayerOp>(op, layerHandle);
        BakedOpState* bakedState = BakedOpState::directConstruct(
                mAllocator, &(currentLayer().repaintClip), dstRect, *copyToOp);
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::CopyToLayer);

        /**
         * Defer a clear rect, so that clears from multiple unclipped layers can be drawn
         * both 1) simultaneously, and 2) as long after the copyToLayer executes as possible
         */
        currentLayer().deferLayerClear(dstRect);

        /**
         * And stash an operation to copy that layer back under the rendertarget until
         * a balanced EndUnclippedLayerOp is seen
         */
        auto copyFromOp = mAllocator.create_trivial<CopyFromLayerOp>(op, layerHandle);
        bakedState = BakedOpState::directConstruct(mAllocator, &(currentLayer().repaintClip),
                                                   dstRect, *copyFromOp);
        currentLayer().activeUnclippedSaveLayers.push_back(bakedState);
    }
}

void FrameBuilder::deferEndUnclippedLayerOp(const EndUnclippedLayerOp& /* ignored */) {
    LOG_ALWAYS_FATAL_IF(currentLayer().activeUnclippedSaveLayers.empty(), "no layer to end!");

    BakedOpState* copyFromLayerOp = currentLayer().activeUnclippedSaveLayers.back();
    currentLayer().activeUnclippedSaveLayers.pop_back();
    if (copyFromLayerOp) {
        currentLayer().deferUnmergeableOp(mAllocator, copyFromLayerOp, OpBatchType::CopyFromLayer);
    }
}

void FrameBuilder::finishDefer() {
    mCaches.fontRenderer.endPrecaching();
}

}  // namespace uirenderer
}  // namespace android
