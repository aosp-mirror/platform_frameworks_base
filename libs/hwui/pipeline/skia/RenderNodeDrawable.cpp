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

#include "RenderNodeDrawable.h"
#include <SkPaintFilterCanvas.h>
#include <gui/TraceUtils.h>
#include "RenderNode.h"
#include "SkiaDisplayList.h"
#include "StretchMask.h"
#include "TransformCanvas.h"

#include <include/effects/SkImageFilters.h>

#include <optional>

namespace android {
namespace uirenderer {
namespace skiapipeline {

RenderNodeDrawable::RenderNodeDrawable(RenderNode* node, SkCanvas* canvas, bool composeLayer,
                                       bool inReorderingSection)
        : mRenderNode(node)
        , mRecordedTransform(canvas->getTotalMatrix())
        , mComposeLayer(composeLayer)
        , mInReorderingSection(inReorderingSection) {}

RenderNodeDrawable::~RenderNodeDrawable() {
    // Just here to move the destructor into the cpp file where we can access RenderNode.

    // TODO: Detangle the header nightmare.
}

void RenderNodeDrawable::drawBackwardsProjectedNodes(SkCanvas* canvas,
                                                     const SkiaDisplayList& displayList,
                                                     int nestLevel) const {
    LOG_ALWAYS_FATAL_IF(0 == nestLevel && !displayList.mProjectionReceiver);
    for (auto& child : displayList.mChildNodes) {
        const RenderProperties& childProperties = child.getNodeProperties();

        // immediate children cannot be projected on their parent
        if (childProperties.getProjectBackwards() && nestLevel > 0) {
            SkAutoCanvasRestore acr2(canvas, true);
            // Apply recorded matrix, which is a total matrix saved at recording time to avoid
            // replaying all DL commands.
            canvas->concat(child.getRecordedMatrix());
            child.drawContent(canvas);
        }

        // skip walking sub-nodes if current display list contains a receiver with exception of
        // level 0, which is a known receiver
        if (0 == nestLevel || !displayList.containsProjectionReceiver()) {
            SkAutoCanvasRestore acr(canvas, true);
            SkMatrix nodeMatrix;
            mat4 hwuiMatrix(child.getRecordedMatrix());
            const RenderNode* childNode = child.getRenderNode();
            childNode->applyViewPropertyTransforms(hwuiMatrix);
            hwuiMatrix.copyTo(nodeMatrix);
            canvas->concat(nodeMatrix);
            const SkiaDisplayList* childDisplayList = childNode->getDisplayList().asSkiaDl();
            if (childDisplayList) {
                drawBackwardsProjectedNodes(canvas, *childDisplayList, nestLevel + 1);
            }
        }
    }
}

static void clipOutline(const Outline& outline, SkCanvas* canvas, const SkRect* pendingClip) {
    Rect possibleRect;
    float radius;

    /* To match the existing HWUI behavior we only supports rectangles or
     * rounded rectangles; passing in a more complicated outline fails silently.
     */
    if (!outline.getAsRoundRect(&possibleRect, &radius)) {
        if (pendingClip) {
            canvas->clipRect(*pendingClip);
        }
        return;
    }

    SkRect rect = possibleRect.toSkRect();
    if (radius != 0.0f) {
        if (pendingClip && !pendingClip->contains(rect)) {
            canvas->clipRect(*pendingClip);
        }
        canvas->clipRRect(SkRRect::MakeRectXY(rect, radius, radius), SkClipOp::kIntersect, true);
    } else {
        if (pendingClip) {
            (void)rect.intersect(*pendingClip);
        }
        canvas->clipRect(rect);
    }
}

const RenderProperties& RenderNodeDrawable::getNodeProperties() const {
    return mRenderNode->properties();
}

void RenderNodeDrawable::onDraw(SkCanvas* canvas) {
    // negative and positive Z order are drawn out of order, if this render node drawable is in
    // a reordering section
    if ((!mInReorderingSection) || MathUtils::isZero(mRenderNode->properties().getZ())) {
        this->forceDraw(canvas);
    }
}

class MarkDraw {
public:
    explicit MarkDraw(SkCanvas& canvas, RenderNode& node) : mCanvas(canvas), mNode(node) {
        if (CC_UNLIKELY(Properties::skpCaptureEnabled)) {
            mNode.markDrawStart(mCanvas);
        }
    }
    ~MarkDraw() {
        if (CC_UNLIKELY(Properties::skpCaptureEnabled)) {
            mNode.markDrawEnd(mCanvas);
        }
    }

private:
    SkCanvas& mCanvas;
    RenderNode& mNode;
};

void RenderNodeDrawable::forceDraw(SkCanvas* canvas) const {
    RenderNode* renderNode = mRenderNode.get();
    MarkDraw _marker{*canvas, *renderNode};

    // We only respect the nothingToDraw check when we are composing a layer. This
    // ensures that we paint the layer even if it is not currently visible in the
    // event that the properties change and it becomes visible.
    if ((mProjectedDisplayList == nullptr && !renderNode->isRenderable()) ||
        (renderNode->nothingToDraw() && mComposeLayer)) {
        return;
    }

    SkiaDisplayList* displayList = renderNode->getDisplayList().asSkiaDl();

    SkAutoCanvasRestore acr(canvas, true);
    const RenderProperties& properties = this->getNodeProperties();
    // pass this outline to the children that may clip backward projected nodes
    displayList->mProjectedOutline =
            displayList->containsProjectionReceiver() ? &properties.getOutline() : nullptr;
    if (!properties.getProjectBackwards()) {
        drawContent(canvas);
        if (mProjectedDisplayList) {
            acr.restore();  // draw projected children using parent matrix
            LOG_ALWAYS_FATAL_IF(!mProjectedDisplayList->mProjectedOutline);
            const bool shouldClip = mProjectedDisplayList->mProjectedOutline->getPath();
            SkAutoCanvasRestore acr2(canvas, shouldClip);
            canvas->setMatrix(mProjectedDisplayList->mParentMatrix);
            if (shouldClip) {
                canvas->clipPath(*mProjectedDisplayList->mProjectedOutline->getPath());
            }
            drawBackwardsProjectedNodes(canvas, *mProjectedDisplayList);
        }
    }
    displayList->mProjectedOutline = nullptr;
}

static bool layerNeedsPaint(const sk_sp<SkImage>& snapshotImage, const LayerProperties& properties,
                            float alphaMultiplier, SkPaint* paint) {
    if (alphaMultiplier < 1.0f || properties.alpha() < 255 ||
        properties.xferMode() != SkBlendMode::kSrcOver || properties.getColorFilter() != nullptr ||
        properties.getImageFilter() != nullptr || properties.getStretchEffect().requiresLayer()) {
        paint->setAlpha(properties.alpha() * alphaMultiplier);
        paint->setBlendMode(properties.xferMode());
        paint->setColorFilter(sk_ref_sp(properties.getColorFilter()));

        sk_sp<SkImageFilter> imageFilter = sk_ref_sp(properties.getImageFilter());
        paint->setImageFilter(std::move(imageFilter));
        return true;
    }
    return false;
}

class AlphaFilterCanvas : public SkPaintFilterCanvas {
public:
    AlphaFilterCanvas(SkCanvas* canvas, float alpha) : SkPaintFilterCanvas(canvas), mAlpha(alpha) {}

protected:
    bool onFilter(SkPaint& paint) const override {
        paint.setAlpha((uint8_t)paint.getAlpha() * mAlpha);
        return true;
    }
    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        // We unroll the drawable using "this" canvas, so that draw calls contained inside will
        // get their alpha applied. The default SkPaintFilterCanvas::onDrawDrawable does not unroll.
        drawable->draw(this, matrix);
    }

private:
    float mAlpha;
};

void RenderNodeDrawable::drawContent(SkCanvas* canvas) const {
    RenderNode* renderNode = mRenderNode.get();
    float alphaMultiplier = 1.0f;
    const RenderProperties& properties = renderNode->properties();

    // If we are drawing the contents of layer, we don't want to apply any of
    // the RenderNode's properties during this pass. Those will all be applied
    // when the layer is composited.
    if (mComposeLayer) {
        setViewProperties(properties, canvas, &alphaMultiplier);
    }
    SkiaDisplayList* displayList = mRenderNode->getDisplayList().asSkiaDl();
    displayList->mParentMatrix = canvas->getTotalMatrix();

    // TODO should we let the bound of the drawable do this for us?
    const SkRect bounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());
    bool quickRejected = properties.getClipToBounds() && canvas->quickReject(bounds);
    if (!quickRejected) {
        SkiaDisplayList* displayList = renderNode->getDisplayList().asSkiaDl();
        const LayerProperties& layerProperties = properties.layerProperties();
        // composing a hardware layer
        if (renderNode->getLayerSurface() && mComposeLayer) {
            SkASSERT(properties.effectiveLayerType() == LayerType::RenderLayer);
            SkPaint paint;
            sk_sp<SkImage> snapshotImage = renderNode->getLayerSurface()->makeImageSnapshot();
            layerNeedsPaint(snapshotImage, layerProperties, alphaMultiplier, &paint);
            SkSamplingOptions sampling(SkFilterMode::kLinear);

            // surfaces for layers are created on LAYER_SIZE boundaries (which are >= layer size) so
            // we need to restrict the portion of the surface drawn to the size of the renderNode.
            SkASSERT(renderNode->getLayerSurface()->width() >= bounds.width());
            SkASSERT(renderNode->getLayerSurface()->height() >= bounds.height());

            // If SKP recording is active save an annotation that indicates this drawImageRect
            // could also be rendered with the commands saved at ID associated with this node.
            if (CC_UNLIKELY(Properties::skpCaptureEnabled)) {
                canvas->drawAnnotation(bounds, String8::format(
                    "SurfaceID|%" PRId64, renderNode->uniqueId()).c_str(), nullptr);
            }

            const StretchEffect& stretch = properties.layerProperties().getStretchEffect();
            if (stretch.isEmpty() ||
                Properties::getStretchEffectBehavior() == StretchEffectBehavior::UniformScale) {
                // If we don't have any stretch effects, issue the filtered
                // canvas draw calls to make sure we still punch a hole
                // with the same canvas transformation + clip into the target
                // canvas then draw the layer on top
                if (renderNode->hasHolePunches()) {
                    TransformCanvas transformCanvas(canvas, SkBlendMode::kClear);
                    displayList->draw(&transformCanvas);
                }
                canvas->drawImageRect(snapshotImage, bounds, bounds, sampling, &paint,
                                      SkCanvas::kStrict_SrcRectConstraint);
            } else {
                // If we do have stretch effects and have hole punches,
                // then create a mask and issue the filtered draw calls to
                // get the corresponding hole punches.
                // Then apply the stretch to the mask and draw the mask to
                // the destination
                if (renderNode->hasHolePunches()) {
                    GrRecordingContext* context = canvas->recordingContext();
                    StretchMask& stretchMask = renderNode->getStretchMask();
                    stretchMask.draw(context,
                                     stretch,
                                     bounds,
                                     displayList,
                                     canvas);
                }

                sk_sp<SkShader> stretchShader = stretch.getShader(bounds.width(),
                                                                  bounds.height(),
                                                                  snapshotImage);
                paint.setShader(stretchShader);
                canvas->drawRect(bounds, paint);
            }

            if (!renderNode->getSkiaLayer()->hasRenderedSinceRepaint) {
                renderNode->getSkiaLayer()->hasRenderedSinceRepaint = true;
                if (CC_UNLIKELY(Properties::debugLayersUpdates)) {
                    SkPaint layerPaint;
                    layerPaint.setColor(0x7f00ff00);
                    canvas->drawRect(bounds, layerPaint);
                } else if (CC_UNLIKELY(Properties::debugOverdraw)) {
                    // Render transparent rect to increment overdraw for repaint area.
                    // This can be "else if" because flashing green on layer updates
                    // will also increment the overdraw if it happens to be turned on.
                    SkPaint transparentPaint;
                    transparentPaint.setColor(SK_ColorTRANSPARENT);
                    canvas->drawRect(bounds, transparentPaint);
                }
            }
        } else {
            if (alphaMultiplier < 1.0f) {
                // Non-layer draw for a view with getHasOverlappingRendering=false, will apply
                // the alpha to the paint of each nested draw.
                AlphaFilterCanvas alphaCanvas(canvas, alphaMultiplier);
                displayList->draw(&alphaCanvas);
            } else {
                displayList->draw(canvas);
            }
        }
    }
}

void RenderNodeDrawable::setViewProperties(const RenderProperties& properties, SkCanvas* canvas,
                                           float* alphaMultiplier) {
    if (properties.getLeft() != 0 || properties.getTop() != 0) {
        canvas->translate(properties.getLeft(), properties.getTop());
    }
    if (properties.getStaticMatrix()) {
        canvas->concat(*properties.getStaticMatrix());
    } else if (properties.getAnimationMatrix()) {
        canvas->concat(*properties.getAnimationMatrix());
    }
    if (properties.hasTransformMatrix()) {
        if (properties.isTransformTranslateOnly()) {
            canvas->translate(properties.getTranslationX(), properties.getTranslationY());
        } else {
            canvas->concat(*properties.getTransformMatrix());
        }
    }
    if (Properties::getStretchEffectBehavior() == StretchEffectBehavior::UniformScale) {
        const StretchEffect& stretch = properties.layerProperties().getStretchEffect();
        if (!stretch.isEmpty()) {
            canvas->concat(
                    stretch.makeLinearStretch(properties.getWidth(), properties.getHeight()));
        }
    }
    const bool isLayer = properties.effectiveLayerType() != LayerType::None;
    int clipFlags = properties.getClippingFlags();
    if (properties.getAlpha() < 1) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS;  // bounds clipping done by layer
        }
        if (CC_LIKELY(isLayer || !properties.getHasOverlappingRendering())) {
            *alphaMultiplier = properties.getAlpha();
        } else {
            // savelayer needed to create an offscreen buffer
            Rect layerBounds(0, 0, properties.getWidth(), properties.getHeight());
            if (clipFlags) {
                properties.getClippingRectForFlags(clipFlags, &layerBounds);
                clipFlags = 0;  // all clipping done by savelayer
            }
            SkRect bounds = SkRect::MakeLTRB(layerBounds.left, layerBounds.top, layerBounds.right,
                                             layerBounds.bottom);
            canvas->saveLayerAlpha(&bounds, (int)(properties.getAlpha() * 255));
        }

        if (CC_UNLIKELY(ATRACE_ENABLED() && properties.promotedToLayer())) {
            // pretend alpha always causes savelayer to warn about
            // performance problem affecting old versions
            ATRACE_FORMAT("alpha caused saveLayer %dx%d", properties.getWidth(),
                          properties.getHeight());
        }
    }

    const SkRect* pendingClip = nullptr;
    SkRect clipRect;

    if (clipFlags) {
        Rect tmpRect;
        properties.getClippingRectForFlags(clipFlags, &tmpRect);
        clipRect = tmpRect.toSkRect();
        pendingClip = &clipRect;
    }

    if (properties.getRevealClip().willClip()) {
        canvas->clipPath(*properties.getRevealClip().getPath(), SkClipOp::kIntersect, true);
    } else if (properties.getOutline().willClip()) {
        clipOutline(properties.getOutline(), canvas, pendingClip);
        pendingClip = nullptr;
    }

    if (pendingClip) {
        canvas->clipRect(*pendingClip);
    }
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
