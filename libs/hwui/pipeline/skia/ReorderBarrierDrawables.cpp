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
#include "SkiaPipeline.h"

#include <SkBlurMask.h>
#include <SkBlurMaskFilter.h>
#include <SkGaussianEdgeShader.h>
#include <SkPathOps.h>
#include <SkRRectsGaussianEdgeMaskFilter.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

StartReorderBarrierDrawable::StartReorderBarrierDrawable(SkiaDisplayList* data)
        : mEndChildIndex(0)
        , mBeginChildIndex(data->mChildNodes.size())
        , mDisplayList(data) {
}

void StartReorderBarrierDrawable::onDraw(SkCanvas* canvas) {
    if (mChildren.empty()) {
        //mChildren is allocated and initialized only the first time onDraw is called and cached for
        //subsequent calls
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

    SkASSERT(!mChildren.empty());

    size_t drawIndex = 0;
    const size_t endIndex = mChildren.size();
    while (drawIndex < endIndex) {
        RenderNodeDrawable* childNode = mChildren[drawIndex];
        SkASSERT(childNode);
        const float casterZ = childNode->getNodeProperties().getZ();
        if (casterZ >= -NON_ZERO_EPSILON) { //draw only children with negative Z
            return;
        }
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
    SkASSERT(!zChildren.empty());

    /**
     * Draw shadows and (potential) casters mostly in order, but allow the shadows of casters
     * with very similar Z heights to draw together.
     *
     * This way, if Views A & B have the same Z height and are both casting shadows, the shadows are
     * underneath both, and neither's shadow is drawn on top of the other.
     */
    size_t drawIndex = 0;

    const size_t endIndex = zChildren.size();
    while (drawIndex < endIndex     //draw only children with positive Z
            && zChildren[drawIndex]->getNodeProperties().getZ() <= NON_ZERO_EPSILON) drawIndex++;
    size_t shadowIndex = drawIndex;

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            const float casterZ = zChildren[shadowIndex]->getNodeProperties().getZ();

            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < SHADOW_DELTA) {
                this->drawShadow(canvas, zChildren[shadowIndex]);
                lastCasterZ = casterZ; // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        RenderNodeDrawable* childNode = zChildren[drawIndex];
        SkASSERT(childNode);
        childNode->forceDraw(canvas);

        drawIndex++;
    }
}

/**
 * @param canvas             the destination for the shadow draws
 * @param shape              the shape casting the shadow
 * @param casterZValue       the Z value of the caster RRect
 * @param ambientAlpha       the maximum alpha value to use when drawing the ambient shadow
 * @param draw               the function used to draw 'shape'
 */
template <typename Shape, typename F>
static void DrawAmbientShadowGeneral(SkCanvas* canvas, const Shape& shape, float casterZValue,
        float ambientAlpha, F&& draw) {
    if (ambientAlpha <= 0) {
        return;
    }

    const float kHeightFactor = 1.f/128.f;
    const float kGeomFactor = 64;

    float umbraAlpha = 1 / (1 + SkMaxScalar(casterZValue*kHeightFactor, 0));
    float radius = casterZValue*kHeightFactor*kGeomFactor;

    sk_sp<SkMaskFilter> mf = SkBlurMaskFilter::Make(kNormal_SkBlurStyle,
            SkBlurMask::ConvertRadiusToSigma(radius), SkBlurMaskFilter::kNone_BlurFlag);
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setMaskFilter(std::move(mf));
    paint.setARGB(ambientAlpha*umbraAlpha, 0, 0, 0);

    draw(shape, paint);
}

/**
 * @param canvas             the destination for the shadow draws
 * @param shape              the shape casting the shadow
 * @param casterZValue       the Z value of the caster RRect
 * @param lightPos           the position of the light casting the shadow
 * @param lightWidth
 * @param spotAlpha          the maximum alpha value to use when drawing the spot shadow
 * @param draw               the function used to draw 'shape'
 */
template <typename Shape, typename F>
static void DrawSpotShadowGeneral(SkCanvas* canvas, const Shape& shape, float casterZValue,
        float spotAlpha, F&& draw) {
    if (spotAlpha <= 0) {
        return;
    }

    const Vector3 lightPos = SkiaPipeline::getLightCenter();
    float zRatio = casterZValue / (lightPos.z - casterZValue);
    // clamp
    if (zRatio < 0.0f) {
        zRatio = 0.0f;
    } else if (zRatio > 0.95f) {
        zRatio = 0.95f;
    }

    float blurRadius = SkiaPipeline::getLightRadius()*zRatio;

    SkAutoCanvasRestore acr(canvas, true);

    sk_sp<SkMaskFilter> mf = SkBlurMaskFilter::Make(kNormal_SkBlurStyle,
            SkBlurMask::ConvertRadiusToSigma(blurRadius), SkBlurMaskFilter::kNone_BlurFlag);

    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setMaskFilter(std::move(mf));
    paint.setARGB(spotAlpha, 0, 0, 0);

    // approximate projection by translating and scaling projected offset of bounds center
    // TODO: compute the actual 2D projection
    SkScalar scale = lightPos.z / (lightPos.z - casterZValue);
    canvas->scale(scale, scale);
    SkPoint center = SkPoint::Make(shape.getBounds().centerX(), shape.getBounds().centerY());
    SkMatrix ctmInverse;
    if (!canvas->getTotalMatrix().invert(&ctmInverse)) {
        ALOGW("Matrix is degenerate. Will not render shadow!");
        return;
    }
    SkPoint lightPos2D = SkPoint::Make(lightPos.x, lightPos.y);
    ctmInverse.mapPoints(&lightPos2D, 1);
    canvas->translate(zRatio*(center.fX - lightPos2D.fX), zRatio*(center.fY - lightPos2D.fY));

    draw(shape, paint);
}

#define MAX_BLUR_RADIUS 16383.75f
#define MAX_PAD         64

/**
 * @param casterRect         the rectangle bounds of the RRect casting the shadow
 * @param casterCornerRadius the x&y radius for all the corners of the RRect casting the shadow
 * @param ambientAlpha       the maximum alpha value to use when drawing the ambient shadow
 * @param spotAlpha          the maximum alpha value to use when drawing the spot shadow
 * @param casterAlpha        the alpha value of the RRect casting the shadow (0.0-1.0 range)
 * @param casterZValue       the Z value of the caster RRect
 * @param scaleFactor        the scale needed to map from src-space to device-space
 * @param canvas             the destination for the shadow draws
 */
static void DrawRRectShadows(const SkRect& casterRect, SkScalar casterCornerRadius,
        SkScalar ambientAlpha, SkScalar spotAlpha, SkScalar casterAlpha, SkScalar casterZValue,
        SkScalar scaleFactor, SkCanvas* canvas) {
    SkASSERT(casterCornerRadius >= 0.0f);

    // For all of these, we need to ensure we have a rrect with radius >= 0.5f in device space
    const SkScalar minRadius = 0.5f / scaleFactor;

    const bool isOval = casterCornerRadius >= std::max(SkScalarHalf(casterRect.width()),
            SkScalarHalf(casterRect.height()));
    const bool isRect = casterCornerRadius <= minRadius;

    sk_sp<SkShader> edgeShader(SkGaussianEdgeShader::Make());

    if (ambientAlpha > 0.0f) {
        static const float kHeightFactor = 1.0f / 128.0f;
        static const float kGeomFactor = 64.0f;

        SkScalar srcSpaceAmbientRadius = casterZValue * kHeightFactor * kGeomFactor;
        // the device-space radius sent to the blur shader must fit in 14.2 fixed point
        if (srcSpaceAmbientRadius*scaleFactor > MAX_BLUR_RADIUS) {
            srcSpaceAmbientRadius = MAX_BLUR_RADIUS/scaleFactor;
        }
        const float umbraAlpha = 1.0f / (1.0f + std::max(casterZValue * kHeightFactor, 0.0f));
        const SkScalar ambientOffset = srcSpaceAmbientRadius * umbraAlpha;

        // For the ambient rrect, we inset the offset rect by half the srcSpaceAmbientRadius
        // to get our stroke shape.
        SkScalar ambientPathOutset = std::max(ambientOffset - srcSpaceAmbientRadius * 0.5f,
                minRadius);

        SkRRect ambientRRect;
        const SkRect temp = casterRect.makeOutset(ambientPathOutset, ambientPathOutset);
        if (isOval) {
            ambientRRect = SkRRect::MakeOval(temp);
        } else if (isRect) {
            ambientRRect = SkRRect::MakeRectXY(temp, ambientPathOutset, ambientPathOutset);
        } else {
            ambientRRect = SkRRect::MakeRectXY(temp, casterCornerRadius + ambientPathOutset,
                    casterCornerRadius + ambientPathOutset);
        }

        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setStyle(SkPaint::kStroke_Style);
        // we outset the stroke a little to cover up AA on the interior edge
        float pad = 0.5f;
        paint.setStrokeWidth(srcSpaceAmbientRadius + 2.0f * pad);
        // handle scale of radius and pad due to CTM
        pad *= scaleFactor;
        const SkScalar devSpaceAmbientRadius = srcSpaceAmbientRadius * scaleFactor;
        SkASSERT(devSpaceAmbientRadius <= MAX_BLUR_RADIUS);
        SkASSERT(pad < MAX_PAD);
        // convert devSpaceAmbientRadius to 14.2 fixed point and place in the R & G components
        // convert pad to 6.2 fixed point and place in the B component
        uint16_t iDevSpaceAmbientRadius = (uint16_t)(4.0f * devSpaceAmbientRadius);
        paint.setColor(SkColorSetARGB((unsigned char) ambientAlpha, iDevSpaceAmbientRadius >> 8,
                iDevSpaceAmbientRadius & 0xff, (unsigned char)(4.0f * pad)));

        paint.setShader(edgeShader);
        canvas->drawRRect(ambientRRect, paint);
    }

    if (spotAlpha > 0.0f) {
        const Vector3 lightPos = SkiaPipeline::getLightCenter();
        float zRatio = casterZValue / (lightPos.z - casterZValue);
        // clamp
        if (zRatio < 0.0f) {
            zRatio = 0.0f;
        } else if (zRatio > 0.95f) {
            zRatio = 0.95f;
        }

        const SkScalar lightWidth = SkiaPipeline::getLightRadius();
        SkScalar srcSpaceSpotRadius = 2.0f * lightWidth * zRatio;
        // the device-space radius sent to the blur shader must fit in 14.2 fixed point
        if (srcSpaceSpotRadius*scaleFactor > MAX_BLUR_RADIUS) {
            srcSpaceSpotRadius = MAX_BLUR_RADIUS/scaleFactor;
        }

        SkRRect spotRRect;
        if (isOval) {
            spotRRect = SkRRect::MakeOval(casterRect);
        } else if (isRect) {
            spotRRect = SkRRect::MakeRectXY(casterRect, minRadius, minRadius);
        } else {
            spotRRect = SkRRect::MakeRectXY(casterRect, casterCornerRadius, casterCornerRadius);
        }

        SkRRect spotShadowRRect;
        // Compute the scale and translation for the spot shadow.
        const SkScalar scale = lightPos.z / (lightPos.z - casterZValue);
        spotRRect.transform(SkMatrix::MakeScale(scale, scale), &spotShadowRRect);

        SkPoint center = SkPoint::Make(spotShadowRRect.rect().centerX(),
                                       spotShadowRRect.rect().centerY());
        SkMatrix ctmInverse;
        if (!canvas->getTotalMatrix().invert(&ctmInverse)) {
            ALOGW("Matrix is degenerate. Will not render spot shadow!");
            return;
        }
        SkPoint lightPos2D = SkPoint::Make(lightPos.x, lightPos.y);
        ctmInverse.mapPoints(&lightPos2D, 1);
        const SkPoint spotOffset = SkPoint::Make(zRatio*(center.fX - lightPos2D.fX),
                zRatio*(center.fY - lightPos2D.fY));

        SkAutoCanvasRestore acr(canvas, true);

        // We want to extend the stroked area in so that it meets up with the caster
        // geometry. The stroked geometry will, by definition already be inset half the
        // stroke width but we also have to account for the scaling.
        // We also add 1/2 to cover up AA on the interior edge.
        SkScalar scaleOffset = (scale - 1.0f) * SkTMax(SkTMax(SkTAbs(casterRect.fLeft),
                SkTAbs(casterRect.fRight)), SkTMax(SkTAbs(casterRect.fTop),
                SkTAbs(casterRect.fBottom)));
        SkScalar insetAmount = spotOffset.length() - (0.5f * srcSpaceSpotRadius) +
                scaleOffset + 0.5f;

        // Compute area
        SkScalar strokeWidth = srcSpaceSpotRadius + insetAmount;
        SkScalar strokedArea = 2.0f*strokeWidth * (spotShadowRRect.width()
                + spotShadowRRect.height());
        SkScalar filledArea = (spotShadowRRect.height() + srcSpaceSpotRadius)
                * (spotShadowRRect.width() + srcSpaceSpotRadius);

        SkPaint paint;
        paint.setAntiAlias(true);

        // If the area of the stroked geometry is larger than the fill geometry, just fill it.
        if (strokedArea > filledArea || casterAlpha < 1.0f || insetAmount < 0.0f) {
            paint.setStyle(SkPaint::kStrokeAndFill_Style);
            paint.setStrokeWidth(srcSpaceSpotRadius);
        } else {
            // Since we can't have unequal strokes, inset the shadow rect so the inner
            // and outer edges of the stroke will land where we want.
            SkRect insetRect = spotShadowRRect.rect().makeInset(insetAmount/2.0f, insetAmount/2.0f);
            SkScalar insetRad = SkTMax(spotShadowRRect.getSimpleRadii().fX - insetAmount/2.0f,
                    minRadius);
            spotShadowRRect = SkRRect::MakeRectXY(insetRect, insetRad, insetRad);
            paint.setStyle(SkPaint::kStroke_Style);
            paint.setStrokeWidth(strokeWidth);
        }

        // handle scale of radius and pad due to CTM
        const SkScalar devSpaceSpotRadius = srcSpaceSpotRadius * scaleFactor;
        SkASSERT(devSpaceSpotRadius <= MAX_BLUR_RADIUS);

        const SkScalar devSpaceSpotPad = 0;
        SkASSERT(devSpaceSpotPad < MAX_PAD);

        // convert devSpaceSpotRadius to 14.2 fixed point and place in the R & G
        // components convert devSpaceSpotPad to 6.2 fixed point and place in the B component
        uint16_t iDevSpaceSpotRadius = (uint16_t)(4.0f * devSpaceSpotRadius);
        paint.setColor(SkColorSetARGB((unsigned char) spotAlpha, iDevSpaceSpotRadius >> 8,
                iDevSpaceSpotRadius & 0xff, (unsigned char)(4.0f * devSpaceSpotPad)));
        paint.setShader(edgeShader);

        canvas->translate(spotOffset.fX, spotOffset.fY);
        canvas->drawRRect(spotShadowRRect, paint);
    }
}

/**
 * @param casterRect         the rectangle bounds of the RRect casting the shadow
 * @param casterCornerRadius the x&y radius for all the corners of the RRect casting the shadow
 * @param ambientAlpha       the maximum alpha value to use when drawing the ambient shadow
 * @param spotAlpha          the maximum alpha value to use when drawing the spot shadow
 * @param casterZValue       the Z value of the caster RRect
 * @param scaleFactor        the scale needed to map from src-space to device-space
 * @param clipRR             the oval or rect with which the drawn roundrect must be intersected
 * @param canvas             the destination for the shadow draws
 */
static void DrawRRectShadowsWithClip(const SkRect& casterRect, SkScalar casterCornerRadius,
        SkScalar ambientAlpha, SkScalar spotAlpha, SkScalar casterZValue, SkScalar scaleFactor,
        const SkRRect& clipRR, SkCanvas* canvas) {
    SkASSERT(casterCornerRadius >= 0.0f);

    const bool isOval = casterCornerRadius >= std::max(SkScalarHalf(casterRect.width()),
            SkScalarHalf(casterRect.height()));

    if (ambientAlpha > 0.0f) {
        static const float kHeightFactor = 1.0f / 128.0f;
        static const float kGeomFactor = 64.0f;

        const SkScalar srcSpaceAmbientRadius = casterZValue * kHeightFactor * kGeomFactor;
        const SkScalar devSpaceAmbientRadius = srcSpaceAmbientRadius * scaleFactor;

        const float umbraAlpha = 1.0f / (1.0f + std::max(casterZValue * kHeightFactor, 0.0f));
        const SkScalar ambientOffset = srcSpaceAmbientRadius * umbraAlpha;

        const SkRect srcSpaceAmbientRect = casterRect.makeOutset(ambientOffset, ambientOffset);
        SkRect devSpaceAmbientRect;
        canvas->getTotalMatrix().mapRect(&devSpaceAmbientRect, srcSpaceAmbientRect);

        SkRRect devSpaceAmbientRRect;
        if (isOval) {
            devSpaceAmbientRRect = SkRRect::MakeOval(devSpaceAmbientRect);
        } else {
            const SkScalar devSpaceCornerRadius = scaleFactor * (casterCornerRadius + ambientOffset);
            devSpaceAmbientRRect = SkRRect::MakeRectXY(devSpaceAmbientRect, devSpaceCornerRadius,
                    devSpaceCornerRadius);
        }

        const SkRect srcSpaceAmbClipRect = clipRR.rect().makeOutset(ambientOffset, ambientOffset);
        SkRect devSpaceAmbClipRect;
        canvas->getTotalMatrix().mapRect(&devSpaceAmbClipRect, srcSpaceAmbClipRect);
        SkRRect devSpaceAmbientClipRR;
        if (clipRR.isOval()) {
            devSpaceAmbientClipRR = SkRRect::MakeOval(devSpaceAmbClipRect);
        } else {
            SkASSERT(clipRR.isRect());
            devSpaceAmbientClipRR = SkRRect::MakeRect(devSpaceAmbClipRect);
        }

        SkRect cover = srcSpaceAmbClipRect;
        if (!cover.intersect(srcSpaceAmbientRect)) {
            return;
        }

        SkPaint paint;
        paint.setColor(SkColorSetARGB((unsigned char) ambientAlpha, 0, 0, 0));
        paint.setMaskFilter(SkRRectsGaussianEdgeMaskFilter::Make(devSpaceAmbientRRect,
            devSpaceAmbientClipRR, devSpaceAmbientRadius));
        canvas->drawRect(cover, paint);
    }

    if (spotAlpha > 0.0f) {
        const Vector3 lightPos = SkiaPipeline::getLightCenter();
        float zRatio = casterZValue / (lightPos.z - casterZValue);
        // clamp
        if (zRatio < 0.0f) {
            zRatio = 0.0f;
        } else if (zRatio > 0.95f) {
            zRatio = 0.95f;
        }

        const SkScalar lightWidth = SkiaPipeline::getLightRadius();
        const SkScalar srcSpaceSpotRadius = 2.0f * lightWidth * zRatio;
        const SkScalar devSpaceSpotRadius = srcSpaceSpotRadius * scaleFactor;

        // Compute the scale and translation for the spot shadow.
        const SkScalar scale = lightPos.z / (lightPos.z - casterZValue);
        const SkMatrix spotMatrix = SkMatrix::MakeScale(scale, scale);

        SkRect srcSpaceScaledRect = casterRect;
        spotMatrix.mapRect(&srcSpaceScaledRect);
        srcSpaceScaledRect.outset(SkScalarHalf(srcSpaceSpotRadius),
                SkScalarHalf(srcSpaceSpotRadius));

        SkRRect srcSpaceSpotRRect;
        if (isOval) {
            srcSpaceSpotRRect = SkRRect::MakeOval(srcSpaceScaledRect);
        } else {
            srcSpaceSpotRRect = SkRRect::MakeRectXY(srcSpaceScaledRect, casterCornerRadius * scale,
                    casterCornerRadius * scale);
        }

        SkPoint center = SkPoint::Make(srcSpaceSpotRRect.rect().centerX(),
                srcSpaceSpotRRect.rect().centerY());
        SkMatrix ctmInverse;
        if (!canvas->getTotalMatrix().invert(&ctmInverse)) {
            ALOGW("Matrix is degenerate. Will not render spot shadow!");
            return;
        }
        SkPoint lightPos2D = SkPoint::Make(lightPos.x, lightPos.y);
        ctmInverse.mapPoints(&lightPos2D, 1);
        const SkPoint spotOffset = SkPoint::Make(zRatio*(center.fX - lightPos2D.fX),
                zRatio*(center.fY - lightPos2D.fY));

        SkAutoCanvasRestore acr(canvas, true);
        canvas->translate(spotOffset.fX, spotOffset.fY);

        SkRect devSpaceScaledRect;
        canvas->getTotalMatrix().mapRect(&devSpaceScaledRect, srcSpaceScaledRect);

        SkRRect devSpaceSpotRRect;
        if (isOval) {
            devSpaceSpotRRect = SkRRect::MakeOval(devSpaceScaledRect);
        } else {
            const SkScalar devSpaceScaledCornerRadius = casterCornerRadius * scale * scaleFactor;
            devSpaceSpotRRect = SkRRect::MakeRectXY(devSpaceScaledRect, devSpaceScaledCornerRadius,
                    devSpaceScaledCornerRadius);
        }

        SkPaint paint;
        paint.setColor(SkColorSetARGB((unsigned char) spotAlpha, 0, 0, 0));

        SkRect srcSpaceScaledClipRect = clipRR.rect();
        spotMatrix.mapRect(&srcSpaceScaledClipRect);
        srcSpaceScaledClipRect.outset(SkScalarHalf(srcSpaceSpotRadius),
                SkScalarHalf(srcSpaceSpotRadius));

        SkRect devSpaceScaledClipRect;
        canvas->getTotalMatrix().mapRect(&devSpaceScaledClipRect, srcSpaceScaledClipRect);
        SkRRect devSpaceSpotClipRR;
        if (clipRR.isOval()) {
            devSpaceSpotClipRR = SkRRect::MakeOval(devSpaceScaledClipRect);
        } else {
            SkASSERT(clipRR.isRect());
            devSpaceSpotClipRR = SkRRect::MakeRect(devSpaceScaledClipRect);
        }

        paint.setMaskFilter(SkRRectsGaussianEdgeMaskFilter::Make(devSpaceSpotRRect,
            devSpaceSpotClipRR, devSpaceSpotRadius));

        SkRect cover = srcSpaceScaledClipRect;
        if (!cover.intersect(srcSpaceSpotRRect.rect())) {
            return;
        }

        canvas->drawRect(cover, paint);
    }
}

/**
 * @param casterRect         the rectangle bounds of the RRect casting the shadow
 * @param casterCornerRadius the x&y radius for all the corners of the RRect casting the shadow
 * @param casterClipRect     a rectangular clip that must be intersected with the
 *                           shadow-casting RRect prior to casting the shadow
 * @param revealClip         a circular clip that must be interested with the castClipRect
 *                           and the shadow-casting rect prior to casting the shadow
 * @param ambientAlpha       the maximum alpha value to use when drawing the ambient shadow
 * @param spotAlpha          the maximum alpha value to use when drawing the spot shadow
 * @param casterAlpha        the alpha value of the RRect casting the shadow (0.0-1.0 range)
 * @param casterZValue       the Z value of the caster RRect
 * @param canvas             the destination for the shadow draws
 *
 * We have special cases for 4 round rect shadow draws:
 *    1) a RRect clipped by a reveal animation
 *    2) a RRect clipped by a rectangle
 *    3) an unclipped RRect with non-uniform scale
 *    4) an unclipped RRect with uniform scale
 * 1,2 and 4 require that the scale is uniform.
 * 1 and 2 require that rects stay rects.
 */
static bool DrawShadowsAsRRects(const SkRect& casterRect, SkScalar casterCornerRadius,
        const SkRect& casterClipRect, const RevealClip& revealClip, SkScalar ambientAlpha,
        SkScalar spotAlpha, SkScalar casterAlpha, SkScalar casterZValue, SkCanvas* canvas) {
    SkScalar scaleFactors[2];
    if (!canvas->getTotalMatrix().getMinMaxScales(scaleFactors)) {
        ALOGW("Matrix is degenerate. Will not render shadow!");
        return false;
    }

    // The casterClipRect will be empty when bounds clipping is disabled
    bool casterIsClippedByRect = !casterClipRect.isEmpty();
    bool uniformScale = scaleFactors[0] == scaleFactors[1];

    if (revealClip.willClip()) {
        if (casterIsClippedByRect || !uniformScale || !canvas->getTotalMatrix().rectStaysRect()) {
            return false;  // Fall back to the slow path since PathOps are required
        }

        const float revealRadius = revealClip.getRadius();
        SkRect revealClipRect = SkRect::MakeLTRB(revealClip.getX()-revealRadius,
                revealClip.getY()-revealRadius, revealClip.getX()+revealRadius,
                revealClip.getY()+revealRadius);
        SkRRect revealClipRR = SkRRect::MakeOval(revealClipRect);

        DrawRRectShadowsWithClip(casterRect, casterCornerRadius, ambientAlpha, spotAlpha,
                casterZValue, scaleFactors[0], revealClipRR, canvas);
        return true;
    }

    if (casterIsClippedByRect) {
        if (!uniformScale || !canvas->getTotalMatrix().rectStaysRect()) {
            return false;  // Fall back to the slow path since PathOps are required
        }

        SkRRect casterClipRR = SkRRect::MakeRect(casterClipRect);

        DrawRRectShadowsWithClip(casterRect, casterCornerRadius, ambientAlpha, spotAlpha,
                casterZValue, scaleFactors[0], casterClipRR, canvas);
        return true;
    }

    // The fast path needs uniform scale
    if (!uniformScale) {
        SkRRect casterRR = SkRRect::MakeRectXY(casterRect, casterCornerRadius, casterCornerRadius);
        DrawAmbientShadowGeneral(canvas, casterRR, casterZValue, ambientAlpha,
                [&](const SkRRect& rrect, const SkPaint& paint) {
                    canvas->drawRRect(rrect, paint);
                });
        DrawSpotShadowGeneral(canvas, casterRR, casterZValue, spotAlpha,
                [&](const SkRRect& rrect, const SkPaint& paint) {
                canvas->drawRRect(rrect, paint);
                });
        return true;
    }

    DrawRRectShadows(casterRect, casterCornerRadius, ambientAlpha, spotAlpha, casterAlpha,
            casterZValue, scaleFactors[0], canvas);
    return true;
}

// copied from FrameBuilder::deferShadow
void EndReorderBarrierDrawable::drawShadow(SkCanvas* canvas, RenderNodeDrawable* caster) {
    const RenderProperties& casterProperties = caster->getNodeProperties();

    if (casterProperties.getAlpha() <= 0.0f
            || casterProperties.getOutline().getAlpha() <= 0.0f
            || !casterProperties.getOutline().getPath()
            || casterProperties.getScaleX() == 0
            || casterProperties.getScaleY() == 0) {
        // no shadow to draw
        return;
    }

    const SkScalar casterAlpha = casterProperties.getAlpha()
            * casterProperties.getOutline().getAlpha();
    if (casterAlpha <= 0.0f) {
        return;
    }

    float ambientAlpha = SkiaPipeline::getAmbientShadowAlpha()*casterAlpha;
    float spotAlpha = SkiaPipeline::getSpotShadowAlpha()*casterAlpha;
    const float casterZValue = casterProperties.getZ();

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

    SkMatrix shadowMatrix;
    mat4 hwuiMatrix(caster->getRecordedMatrix());
    // TODO we don't pass the optional boolean to treat it as a 4x4 matrix
    caster->getRenderNode()->applyViewPropertyTransforms(hwuiMatrix);
    hwuiMatrix.copyTo(shadowMatrix);
    canvas->concat(shadowMatrix);

    const Outline& casterOutline = casterProperties.getOutline();
    Rect possibleRect;
    float radius;
    if (casterOutline.getAsRoundRect(&possibleRect, &radius)) {
        if (DrawShadowsAsRRects(possibleRect.toSkRect(), radius, casterClipRect, revealClip,
                ambientAlpha, spotAlpha, casterAlpha, casterZValue, canvas)) {
            return;
        }
    }

    // Hard cases and calls to general shadow code
    const SkPath* casterOutlinePath = casterProperties.getOutline().getPath();

    // holds temporary SkPath to store the result of intersections
    SkPath tmpPath;
    const SkPath* casterPath = casterOutlinePath;

    // TODO: In to following course of code that calculates the final shape, is there an optimal
    //       of doing the Op calculations?
    // intersect the shadow-casting path with the reveal, if present
    if (revealClipPath) {
        Op(*casterPath, *revealClipPath, kIntersect_SkPathOp, &tmpPath);
        casterPath = &tmpPath;
    }

    // intersect the shadow-casting path with the clipBounds, if present
    if (clippedToBounds) {
        SkPath clipBoundsPath;
        clipBoundsPath.addRect(casterClipRect);
        Op(*casterPath, clipBoundsPath, kIntersect_SkPathOp, &tmpPath);
        casterPath = &tmpPath;
    }

    DrawAmbientShadowGeneral(canvas, *casterPath, casterZValue, ambientAlpha,
            [&](const SkPath& path, const SkPaint& paint) {
                canvas->drawPath(path, paint);
            });

    DrawSpotShadowGeneral(canvas, *casterPath, casterZValue, spotAlpha,
            [&](const SkPath& path, const SkPaint& paint) {
                canvas->drawPath(path, paint);
            });
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
