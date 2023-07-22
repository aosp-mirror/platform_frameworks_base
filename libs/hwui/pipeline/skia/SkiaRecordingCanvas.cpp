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
#include <SkBlendMode.h>
#include <SkData.h>
#include <SkDrawable.h>
#include <SkImage.h>
#include <SkImagePriv.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPoint.h>
#include <SkRect.h>
#include <SkRefCnt.h>
#include <SkRRect.h>
#include <SkSamplingOptions.h>
#include <SkTypes.h>
#include "CanvasTransform.h"
#ifdef __ANDROID__ // Layoutlib does not support Layers
#include "Layer.h"
#include "LayerDrawable.h"
#endif
#include "NinePatchUtils.h"
#include "RenderNode.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include "pipeline/skia/BackdropFilterDrawable.h"
#ifdef __ANDROID__ // Layoutlib does not support GL, Vulcan etc.
#include "pipeline/skia/GLFunctorDrawable.h"
#include "pipeline/skia/VkFunctorDrawable.h"
#include "pipeline/skia/VkInteropFunctorDrawable.h"
#endif
#include <log/log.h>
#include <ui/FatVector.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

// ----------------------------------------------------------------------------
// Recording Canvas Setup
// ----------------------------------------------------------------------------

void SkiaRecordingCanvas::initDisplayList(uirenderer::RenderNode* renderNode, int width,
                                          int height) {
    mCurrentBarrier = nullptr;
    LOG_FATAL_IF(mDisplayList.get() != nullptr);

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

void SkiaRecordingCanvas::punchHole(const SkRRect& rect, float alpha) {
    // Add the marker annotation to allow HWUI to determine the current
    // clip/transformation and alpha should be applied
    SkVector vector = rect.getSimpleRadii();
    float data[3];
    data[0] = vector.x();
    data[1] = vector.y();
    data[2] = alpha;
    mRecorder.drawAnnotation(rect.rect(), HOLE_PUNCH_ANNOTATION.c_str(),
                             SkData::MakeWithCopy(data, sizeof(data)));

    // Clear the current rect within the layer itself
    SkPaint paint = SkPaint();
    paint.setColor(SkColors::kBlack);
    paint.setAlphaf(alpha);
    paint.setBlendMode(SkBlendMode::kDstOut);
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

    // draw backdrop filter drawable if needed.
    if (renderNode->stagingProperties().layerProperties().getBackdropImageFilter()) {
        auto* backdropFilterDrawable =
                mDisplayList->allocateDrawable<BackdropFilterDrawable>(renderNode, asSkCanvas());
        drawDrawable(backdropFilterDrawable);
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
    if (sApiLevel <= 27 && paint.asBlendMode() == SkBlendMode::kClear) {
        paint.setBlendMode(SkBlendMode::kDstOut);
    }
}

void SkiaRecordingCanvas::handleMutableImages(Bitmap& bitmap, DrawImagePayload& payload) {
    // if image->unique() is true, then mRecorder.drawImage failed for some reason. It also means
    // it is not safe to store a raw SkImage pointer, because the image object will be destroyed
    // when this function ends.
    if (!bitmap.isImmutable() && payload.image.get() && !payload.image->unique()) {
        mDisplayList->mMutableImages.push_back(payload.image.get());
    }

    if (bitmap.hasGainmap()) {
        auto gainmapBitmap = bitmap.gainmap()->bitmap;
        // Not all DrawImagePayload receivers will store the gainmap (such as DrawImageLattice),
        // so only store it in the mutable list if it was actually recorded
        if (!gainmapBitmap->isImmutable() && payload.gainmapImage.get() &&
            !payload.gainmapImage->unique()) {
            mDisplayList->mMutableImages.push_back(payload.gainmapImage.get());
        }
    }
}

void SkiaRecordingCanvas::onFilterPaint(android::Paint& paint) {
    INHERITED::onFilterPaint(paint);
    SkShader* shader = paint.getShader();
    // TODO(b/264559422): This only works for very specifically a BitmapShader.
    //  It's better than nothing, though
    SkImage* image = shader ? shader->isAImage(nullptr, nullptr) : nullptr;
    if (image) {
        mDisplayList->mMutableImages.push_back(image);
    }
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, float left, float top, const Paint* paint) {
    auto payload = DrawImagePayload(bitmap);

    applyLooper(
            paint,
            [&](const Paint& p) {
                mRecorder.drawImage(DrawImagePayload(payload), left, top, p.sampling(), &p);
            },
            FilterForImage);

    handleMutableImages(bitmap, payload);
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const Paint* paint) {
    SkAutoCanvasRestore acr(&mRecorder, true);
    concat(matrix);

    auto payload = DrawImagePayload(bitmap);

    applyLooper(
            paint,
            [&](const Paint& p) {
                mRecorder.drawImage(DrawImagePayload(payload), 0, 0, p.sampling(), &p);
            },
            FilterForImage);

    handleMutableImages(bitmap, payload);
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                                     float srcBottom, float dstLeft, float dstTop, float dstRight,
                                     float dstBottom, const Paint* paint) {
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);

    auto payload = DrawImagePayload(bitmap);

    applyLooper(
            paint,
            [&](const Paint& p) {
                mRecorder.drawImageRect(DrawImagePayload(payload), srcRect, dstRect, p.sampling(),
                                        &p, SkCanvas::kFast_SrcRectConstraint);
            },
            FilterForImage);

    handleMutableImages(bitmap, payload);
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

    // Most times, we do not have very many flags/colors, so the stack allocated part of
    // FatVector will save us a heap allocation.
    FatVector<SkCanvas::Lattice::RectType, 25> flags(numFlags);
    FatVector<SkColor, 25> colors(numFlags);
    if (numFlags > 0) {
        NinePatchUtils::SetLatticeFlags(&lattice, flags.data(), numFlags, chunk, colors.data());
    }

    lattice.fBounds = nullptr;
    SkRect dst = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    auto payload = DrawImagePayload(bitmap);

    // HWUI always draws 9-patches with linear filtering, regardless of the Paint.
    const SkFilterMode filter = SkFilterMode::kLinear;

    applyLooper(
            paint,
            [&](const SkPaint& p) {
                mRecorder.drawImageLattice(DrawImagePayload(payload), lattice, dst, filter, &p);
            },
            FilterForImage);

    handleMutableImages(bitmap, payload);
}

double SkiaRecordingCanvas::drawAnimatedImage(AnimatedImageDrawable* animatedImage) {
    drawDrawable(animatedImage);
    mDisplayList->mAnimatedImages.push_back(animatedImage);
    return 0;
}

void SkiaRecordingCanvas::drawMesh(const Mesh& mesh, sk_sp<SkBlender> blender, const Paint& paint) {
    mDisplayList->mMeshes.push_back(&mesh);
    mRecorder.drawMesh(mesh, blender, paint);
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
