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

#include "SkiaRecordingCanvas.h"
#include "hwui/Paint.h"
#include <SkImagePriv.h>
#include "CanvasTransform.h"
#ifdef __ANDROID__ // Layoutlib does not support Layers
#include "Layer.h"
#include "LayerDrawable.h"
#endif
#include "NinePatchUtils.h"
#include "RenderNode.h"
#include "pipeline/skia/AnimatedDrawables.h"
#ifdef __ANDROID__ // Layoutlib does not support GL, Vulcan etc.
#include "pipeline/skia/GLFunctorDrawable.h"
#include "pipeline/skia/VkFunctorDrawable.h"
#include "pipeline/skia/VkInteropFunctorDrawable.h"
#endif

namespace android {
namespace uirenderer {
namespace skiapipeline {

// ----------------------------------------------------------------------------
// Recording Canvas Setup
// ----------------------------------------------------------------------------

void SkiaRecordingCanvas::initDisplayList(uirenderer::RenderNode* renderNode, int width,
                                          int height) {
    mCurrentBarrier = nullptr;
    SkASSERT(mDisplayList.get() == nullptr);

    if (renderNode) {
        mDisplayList = renderNode->detachAvailableList();
    }
    if (!mDisplayList) {
        mDisplayList.reset(new SkiaDisplayList());
    }

    mDisplayList->attachRecorder(&mRecorder, SkIRect::MakeWH(width, height));
    SkiaCanvas::reset(&mRecorder);
    mDisplayList->setHasHolePunches(false);
}

void SkiaRecordingCanvas::punchHole(const SkRRect& rect) {
    // Add the marker annotation to allow HWUI to determine where the current
    // clip/transformation should be applied
    SkVector vector = rect.getSimpleRadii();
    float data[2];
    data[0] = vector.x();
    data[1] = vector.y();
    mRecorder.drawAnnotation(rect.rect(), HOLE_PUNCH_ANNOTATION.c_str(),
                             SkData::MakeWithCopy(data, 2 * sizeof(float)));

    // Clear the current rect within the layer itself
    SkPaint paint = SkPaint();
    paint.setColor(0);
    paint.setBlendMode(SkBlendMode::kClear);
    mRecorder.drawRRect(rect, paint);

    mDisplayList->setHasHolePunches(true);
}

std::unique_ptr<SkiaDisplayList> SkiaRecordingCanvas::finishRecording() {
    // close any existing chunks if necessary
    enableZ(false);
    mRecorder.restoreToCount(1);
    return std::move(mDisplayList);
}

void SkiaRecordingCanvas::finishRecording(uirenderer::RenderNode* destination) {
    destination->setStagingDisplayList(uirenderer::DisplayList(finishRecording()));
}

// ----------------------------------------------------------------------------
// Recording Canvas draw operations: View System
// ----------------------------------------------------------------------------

void SkiaRecordingCanvas::drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
                                        uirenderer::CanvasPropertyPrimitive* top,
                                        uirenderer::CanvasPropertyPrimitive* right,
                                        uirenderer::CanvasPropertyPrimitive* bottom,
                                        uirenderer::CanvasPropertyPrimitive* rx,
                                        uirenderer::CanvasPropertyPrimitive* ry,
                                        uirenderer::CanvasPropertyPaint* paint) {
    // Destructor of drawables created with allocateDrawable, will be invoked by ~LinearAllocator.
    drawDrawable(mDisplayList->allocateDrawable<AnimatedRoundRect>(left, top, right, bottom, rx, ry,
                                                                   paint));
}

void SkiaRecordingCanvas::drawCircle(uirenderer::CanvasPropertyPrimitive* x,
                                     uirenderer::CanvasPropertyPrimitive* y,
                                     uirenderer::CanvasPropertyPrimitive* radius,
                                     uirenderer::CanvasPropertyPaint* paint) {
    drawDrawable(mDisplayList->allocateDrawable<AnimatedCircle>(x, y, radius, paint));
}

void SkiaRecordingCanvas::drawRipple(const skiapipeline::RippleDrawableParams& params) {
    mRecorder.drawRippleDrawable(params);
}

void SkiaRecordingCanvas::enableZ(bool enableZ) {
    if (mCurrentBarrier && enableZ) {
        // Already in a re-order section, nothing to do
        return;
    }

    if (nullptr != mCurrentBarrier) {
        // finish off the existing chunk
        SkDrawable* drawable =
                mDisplayList->allocateDrawable<EndReorderBarrierDrawable>(mCurrentBarrier);
        mCurrentBarrier = nullptr;
        drawDrawable(drawable);
    }
    if (enableZ) {
        mCurrentBarrier =
                mDisplayList->allocateDrawable<StartReorderBarrierDrawable>(mDisplayList.get());
        drawDrawable(mCurrentBarrier);
    }
}

void SkiaRecordingCanvas::drawLayer(uirenderer::DeferredLayerUpdater* layerUpdater) {
#ifdef __ANDROID__ // Layoutlib does not support Layers
    if (layerUpdater != nullptr) {
        // Create a ref-counted drawable, which is kept alive by sk_sp in SkLiteDL.
        sk_sp<SkDrawable> drawable(new LayerDrawable(layerUpdater));
        drawDrawable(drawable.get());
    }
#endif
}


void SkiaRecordingCanvas::drawRenderNode(uirenderer::RenderNode* renderNode) {
    // Record the child node. Drawable dtor will be invoked when mChildNodes deque is cleared.
    mDisplayList->mChildNodes.emplace_back(renderNode, asSkCanvas(), true, mCurrentBarrier);
    auto& renderNodeDrawable = mDisplayList->mChildNodes.back();
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
        // Put Vulkan WebViews with non-rectangular clips in a HW layer
        renderNode->mutateStagingProperties().setClipMayBeComplex(mRecorder.isClipMayBeComplex());
    }
    drawDrawable(&renderNodeDrawable);

    // use staging property, since recording on UI thread
    if (renderNode->stagingProperties().isProjectionReceiver()) {
        mDisplayList->mProjectionReceiver = &renderNodeDrawable;
    }
}

void SkiaRecordingCanvas::drawWebViewFunctor(int functor) {
#ifdef __ANDROID__ // Layoutlib does not support GL, Vulcan etc.
    FunctorDrawable* functorDrawable;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
        functorDrawable = mDisplayList->allocateDrawable<VkFunctorDrawable>(functor, asSkCanvas());
    } else {
        functorDrawable = mDisplayList->allocateDrawable<GLFunctorDrawable>(functor, asSkCanvas());
    }
    mDisplayList->mChildFunctors.push_back(functorDrawable);
    mRecorder.drawWebView(functorDrawable);
#endif
}

void SkiaRecordingCanvas::drawVectorDrawable(VectorDrawableRoot* tree) {
    mRecorder.drawVectorDrawable(tree);
    SkMatrix mat;
    this->getMatrix(&mat);
    mDisplayList->appendVD(tree, mat);
}

// ----------------------------------------------------------------------------
// Recording Canvas draw operations: Bitmaps
// ----------------------------------------------------------------------------

void SkiaRecordingCanvas::FilterForImage(SkPaint& paint) {
    // kClear blend mode is drawn as kDstOut on HW for compatibility with Android O and
    // older.
    if (sApiLevel <= 27 && paint.getBlendMode() == SkBlendMode::kClear) {
        paint.setBlendMode(SkBlendMode::kDstOut);
    }
}

static SkFilterMode Paint_to_filter(const SkPaint& paint) {
    return paint.getFilterQuality() != kNone_SkFilterQuality ? SkFilterMode::kLinear
                                                             : SkFilterMode::kNearest;
}

static SkSamplingOptions Paint_to_sampling(const SkPaint& paint) {
    // Android only has 1-bit for "filter", so we don't try to cons-up mipmaps or cubics
    return SkSamplingOptions(Paint_to_filter(paint), SkMipmapMode::kNone);
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, float left, float top, const Paint* paint) {
    sk_sp<SkImage> image = bitmap.makeImage();

    applyLooper(
            paint,
            [&](const SkPaint& p) {
                mRecorder.drawImage(image, left, top, Paint_to_sampling(p), &p, bitmap.palette());
            },
            FilterForImage);

    // if image->unique() is true, then mRecorder.drawImage failed for some reason. It also means
    // it is not safe to store a raw SkImage pointer, because the image object will be destroyed
    // when this function ends.
    if (!bitmap.isImmutable() && image.get() && !image->unique()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const Paint* paint) {
    SkAutoCanvasRestore acr(&mRecorder, true);
    concat(matrix);

    sk_sp<SkImage> image = bitmap.makeImage();

    applyLooper(
            paint,
            [&](const SkPaint& p) {
                mRecorder.drawImage(image, 0, 0, Paint_to_sampling(p), &p, bitmap.palette());
            },
            FilterForImage);

    if (!bitmap.isImmutable() && image.get() && !image->unique()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                                     float srcBottom, float dstLeft, float dstTop, float dstRight,
                                     float dstBottom, const Paint* paint) {
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);

    sk_sp<SkImage> image = bitmap.makeImage();

    applyLooper(
            paint,
            [&](const SkPaint& p) {
                mRecorder.drawImageRect(image, srcRect, dstRect, Paint_to_sampling(p), &p,
                                        SkCanvas::kFast_SrcRectConstraint, bitmap.palette());
            },
            FilterForImage);

    if (!bitmap.isImmutable() && image.get() && !image->unique() && !srcRect.isEmpty() &&
        !dstRect.isEmpty()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

void SkiaRecordingCanvas::drawNinePatch(Bitmap& bitmap, const Res_png_9patch& chunk, float dstLeft,
                                        float dstTop, float dstRight, float dstBottom,
                                        const Paint* paint) {
    SkCanvas::Lattice lattice;
    NinePatchUtils::SetLatticeDivs(&lattice, chunk, bitmap.width(), bitmap.height());

    lattice.fRectTypes = nullptr;
    lattice.fColors = nullptr;
    int numFlags = 0;
    if (chunk.numColors > 0 && chunk.numColors == NinePatchUtils::NumDistinctRects(lattice)) {
        // We can expect the framework to give us a color for every distinct rect.
        // Skia requires placeholder flags for degenerate rects.
        numFlags = (lattice.fXCount + 1) * (lattice.fYCount + 1);
    }

    SkAutoSTMalloc<25, SkCanvas::Lattice::RectType> flags(numFlags);
    SkAutoSTMalloc<25, SkColor> colors(numFlags);
    if (numFlags > 0) {
        NinePatchUtils::SetLatticeFlags(&lattice, flags.get(), numFlags, chunk, colors.get());
    }

    lattice.fBounds = nullptr;
    SkRect dst = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    sk_sp<SkImage> image = bitmap.makeImage();

    // HWUI always draws 9-patches with linear filtering, regardless of the Paint.
    const SkFilterMode filter = SkFilterMode::kLinear;

    applyLooper(
            paint,
            [&](const SkPaint& p) {
                mRecorder.drawImageLattice(image, lattice, dst, filter, &p, bitmap.palette());
            },
            FilterForImage);

    if (!bitmap.isImmutable() && image.get() && !image->unique() && !dst.isEmpty()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

double SkiaRecordingCanvas::drawAnimatedImage(AnimatedImageDrawable* animatedImage) {
    drawDrawable(animatedImage);
    mDisplayList->mAnimatedImages.push_back(animatedImage);
    return 0;
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
