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

#include "ReorderBarrierDrawables.h"
#include "RenderNode.h"
#include "SkiaDisplayList.h"
#include "LightingInfo.h"

#include <SkPathOps.h>
#include <SkShadowUtils.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

StartReorderBarrierDrawable::StartReorderBarrierDrawable(SkiaDisplayList* data)
        : mEndChildIndex(-1), mBeginChildIndex(data->mChildNodes.size()), mDisplayList(data) {}

void StartReorderBarrierDrawable::onDraw(SkCanvas* canvas) {
    if (mChildren.empty()) {
        // mChildren is allocated and initialized only the first time onDraw is called and cached
        // for
        // subsequent calls
        mChildren.reserve(mEndChildIndex - mBeginChildIndex + 1);
        for (int i = mBeginChildIndex; i <= mEndChildIndex; i++) {
            mChildren.push_back(const_cast<RenderNodeDrawable*>(&mDisplayList->mChildNodes[i]));
        }
    }
    std::stable_sort(mChildren.begin(), mChildren.end(),
                     [](RenderNodeDrawable* a, RenderNodeDrawable* b) {
                         const float aZValue = a->getNodeProperties().getZ();
                         const float bZValue = b->getNodeProperties().getZ();
                         return aZValue < bZValue;
                     });

    size_t drawIndex = 0;
    const size_t endIndex = mChildren.size();
    while (drawIndex < endIndex) {
        RenderNodeDrawable* childNode = mChildren[drawIndex];
        SkASSERT(childNode);
        const float casterZ = childNode->getNodeProperties().getZ();
        if (casterZ >= -MathUtils::NON_ZERO_EPSILON) {  // draw only children with negative Z
            return;
        }
        SkAutoCanvasRestore acr(canvas, true);
        // Since we're drawing out of recording order, the child's matrix needs to be applied to the
        // canvas. In in-order drawing, the canvas already has the child's matrix applied.
        canvas->setMatrix(mDisplayList->mParentMatrix);
        canvas->concat(childNode->getRecordedMatrix());
        childNode->forceDraw(canvas);
        drawIndex++;
    }
}

EndReorderBarrierDrawable::EndReorderBarrierDrawable(StartReorderBarrierDrawable* startBarrier)
        : mStartBarrier(startBarrier) {
    mStartBarrier->mEndChildIndex = mStartBarrier->mDisplayList->mChildNodes.size() - 1;
}

#define SHADOW_DELTA 0.1f

void EndReorderBarrierDrawable::onDraw(SkCanvas* canvas) {
    auto& zChildren = mStartBarrier->mChildren;

    /**
     * Draw shadows and (potential) casters mostly in order, but allow the shadows of casters
     * with very similar Z heights to draw together.
     *
     * This way, if Views A & B have the same Z height and are both casting shadows, the shadows are
     * underneath both, and neither's shadow is drawn on top of the other.
     */
    size_t drawIndex = 0;

    const size_t endIndex = zChildren.size();
    while (drawIndex < endIndex  // draw only children with positive Z
           && zChildren[drawIndex]->getNodeProperties().getZ() <= MathUtils::NON_ZERO_EPSILON)
        drawIndex++;
    size_t shadowIndex = drawIndex;

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            const float casterZ = zChildren[shadowIndex]->getNodeProperties().getZ();

            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < SHADOW_DELTA) {
                this->drawShadow(canvas, zChildren[shadowIndex]);
                lastCasterZ = casterZ;  // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        RenderNodeDrawable* childNode = zChildren[drawIndex];
        SkASSERT(childNode);
        SkAutoCanvasRestore acr(canvas, true);
        // Since we're drawing out of recording order, the child's matrix needs to be applied to the
        // canvas. In in-order drawing, the canvas already has the child's matrix applied.
        canvas->setMatrix(mStartBarrier->mDisplayList->mParentMatrix);
        canvas->concat(childNode->getRecordedMatrix());
        childNode->forceDraw(canvas);

        drawIndex++;
    }
}

static SkColor multiplyAlpha(SkColor color, float alpha) {
    return SkColorSetA(color, alpha * SkColorGetA(color));
}

// copied from FrameBuilder::deferShadow
void EndReorderBarrierDrawable::drawShadow(SkCanvas* canvas, RenderNodeDrawable* caster) {
    const RenderProperties& casterProperties = caster->getNodeProperties();

    if (casterProperties.getAlpha() <= 0.0f || casterProperties.getOutline().getAlpha() <= 0.0f ||
        !casterProperties.getOutline().getPath() || casterProperties.getScaleX() == 0 ||
        casterProperties.getScaleY() == 0) {
        // no shadow to draw
        return;
    }

    const SkScalar casterAlpha =
            casterProperties.getAlpha() * casterProperties.getOutline().getAlpha();
    if (casterAlpha <= 0.0f) {
        return;
    }

    float ambientAlpha = (LightingInfo::getAmbientShadowAlpha() / 255.f) * casterAlpha;
    float spotAlpha = (LightingInfo::getSpotShadowAlpha() / 255.f) * casterAlpha;

    const RevealClip& revealClip = casterProperties.getRevealClip();
    const SkPath* revealClipPath = revealClip.getPath();
    if (revealClipPath && revealClipPath->isEmpty()) {
        // An empty reveal clip means nothing is drawn
        return;
    }

    bool clippedToBounds = casterProperties.getClippingFlags() & CLIP_TO_CLIP_BOUNDS;

    SkRect casterClipRect = SkRect::MakeEmpty();
    if (clippedToBounds) {
        Rect clipBounds;
        casterProperties.getClippingRectForFlags(CLIP_TO_CLIP_BOUNDS, &clipBounds);
        casterClipRect = clipBounds.toSkRect();
        if (casterClipRect.isEmpty()) {
            // An empty clip rect means nothing is drawn
            return;
        }
    }

    SkAutoCanvasRestore acr(canvas, true);
    // Since we're drawing out of recording order, the child's matrix needs to be applied to the
    // canvas. In in-order drawing, the canvas already has the child's matrix applied.
    canvas->setMatrix(mStartBarrier->mDisplayList->mParentMatrix);

    SkMatrix shadowMatrix;
    mat4 hwuiMatrix(caster->getRecordedMatrix());
    // TODO we don't pass the optional boolean to treat it as a 4x4 matrix
    // applyViewPropertyTransforms gets the same matrix, which render nodes apply with
    // RenderNodeDrawable::setViewProperties as a part if their draw.
    caster->getRenderNode()->applyViewPropertyTransforms(hwuiMatrix);
    hwuiMatrix.copyTo(shadowMatrix);
    canvas->concat(shadowMatrix);

    // default the shadow-casting path to the outline of the caster
    const SkPath* casterPath = casterProperties.getOutline().getPath();

    // intersect the shadow-casting path with the clipBounds, if present
    if (clippedToBounds && !casterClipRect.contains(casterPath->getBounds())) {
        casterPath = caster->getRenderNode()->getClippedOutline(casterClipRect);
    }

    // intersect the shadow-casting path with the reveal, if present
    SkPath tmpPath;  // holds temporary SkPath to store the result of intersections
    if (revealClipPath) {
        Op(*casterPath, *revealClipPath, kIntersect_SkPathOp, &tmpPath);
        tmpPath.setIsVolatile(true);
        casterPath = &tmpPath;
    }

    const Vector3 lightPos = LightingInfo::getLightCenter();
    SkPoint3 skiaLightPos = SkPoint3::Make(lightPos.x, lightPos.y, lightPos.z);
    SkPoint3 zParams;
    if (shadowMatrix.hasPerspective()) {
        // get the matrix with the full 3D transform
        mat4 zMatrix;
        caster->getRenderNode()->applyViewPropertyTransforms(zMatrix, true);
        zParams = SkPoint3::Make(zMatrix[2], zMatrix[6], zMatrix[mat4::kTranslateZ]);
    } else {
        zParams = SkPoint3::Make(0, 0, casterProperties.getZ());
    }
    SkColor ambientColor = multiplyAlpha(casterProperties.getAmbientShadowColor(), ambientAlpha);
    SkColor spotColor = multiplyAlpha(casterProperties.getSpotShadowColor(), spotAlpha);
    SkShadowUtils::DrawShadow(
            canvas, *casterPath, zParams, skiaLightPos, LightingInfo::getLightRadius(),
            ambientColor, spotColor,
            casterAlpha < 1.0f ? SkShadowFlags::kTransparentOccluder_ShadowFlag : 0);
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
