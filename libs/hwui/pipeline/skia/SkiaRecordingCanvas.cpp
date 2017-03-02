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

#include "Layer.h"
#include "RenderNode.h"
#include "LayerDrawable.h"
#include "NinePatchUtils.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include <SkImagePriv.h>

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
}

uirenderer::DisplayList* SkiaRecordingCanvas::finishRecording() {
    // close any existing chunks if necessary
    insertReorderBarrier(false);
    mRecorder.restoreToCount(1);
    return mDisplayList.release();
}

// ----------------------------------------------------------------------------
// Recording Canvas draw operations: View System
// ----------------------------------------------------------------------------

void SkiaRecordingCanvas::drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
        uirenderer::CanvasPropertyPrimitive* top, uirenderer::CanvasPropertyPrimitive* right,
        uirenderer::CanvasPropertyPrimitive* bottom, uirenderer::CanvasPropertyPrimitive* rx,
        uirenderer::CanvasPropertyPrimitive* ry, uirenderer::CanvasPropertyPaint* paint) {
    drawDrawable(mDisplayList->allocateDrawable<AnimatedRoundRect>(left, top, right, bottom,
            rx, ry, paint));
}

void SkiaRecordingCanvas::drawCircle(uirenderer::CanvasPropertyPrimitive* x,
        uirenderer::CanvasPropertyPrimitive* y, uirenderer::CanvasPropertyPrimitive* radius,
        uirenderer::CanvasPropertyPaint* paint) {
    drawDrawable(mDisplayList->allocateDrawable<AnimatedCircle>(x, y, radius, paint));
}

void SkiaRecordingCanvas::insertReorderBarrier(bool enableReorder) {
    if (nullptr != mCurrentBarrier) {
        // finish off the existing chunk
        SkDrawable* drawable =
                mDisplayList->allocateDrawable<EndReorderBarrierDrawable>(
                mCurrentBarrier);
        mCurrentBarrier = nullptr;
        drawDrawable(drawable);
    }
    if (enableReorder) {
        mCurrentBarrier = (StartReorderBarrierDrawable*)
                mDisplayList->allocateDrawable<StartReorderBarrierDrawable>(
                mDisplayList.get());
        drawDrawable(mCurrentBarrier);
    }
}

void SkiaRecordingCanvas::drawLayer(uirenderer::DeferredLayerUpdater* layerUpdater) {
    if (layerUpdater != nullptr && layerUpdater->backingLayer() != nullptr) {
        uirenderer::Layer* layer = layerUpdater->backingLayer();
        sk_sp<SkDrawable> drawable(new LayerDrawable(layer));
        drawDrawable(drawable.get());
    }
}

void SkiaRecordingCanvas::drawRenderNode(uirenderer::RenderNode* renderNode) {
    // record the child node
    mDisplayList->mChildNodes.emplace_back(renderNode, asSkCanvas(), true, mCurrentBarrier);
    auto& renderNodeDrawable = mDisplayList->mChildNodes.back();
    drawDrawable(&renderNodeDrawable);

    // use staging property, since recording on UI thread
    if (renderNode->stagingProperties().isProjectionReceiver()) {
        mDisplayList->mProjectionReceiver = &renderNodeDrawable;
        // set projectionReceiveIndex so that RenderNode.hasProjectionReceiver returns true
        mDisplayList->projectionReceiveIndex = mDisplayList->mChildNodes.size() - 1;
    }
}

void SkiaRecordingCanvas::callDrawGLFunction(Functor* functor,
        uirenderer::GlFunctorLifecycleListener* listener) {
    mDisplayList->mChildFunctors.emplace_back(functor, listener, asSkCanvas());
    drawDrawable(&mDisplayList->mChildFunctors.back());
}

class VectorDrawable : public SkDrawable {
 public:
    VectorDrawable(VectorDrawableRoot* tree) : mRoot(tree) {}

 protected:
     virtual SkRect onGetBounds() override {
         return SkRect::MakeLargest();
     }
     virtual void onDraw(SkCanvas* canvas) override {
         Bitmap& hwuiBitmap = mRoot->getBitmapUpdateIfDirty();
         SkBitmap bitmap;
         hwuiBitmap.getSkBitmap(&bitmap);
         SkPaint* paint = mRoot->getPaint();
         canvas->drawBitmapRect(bitmap, mRoot->mutateProperties()->getBounds(), paint);
         /*
          * TODO we can draw this directly but need to address the following...
          *
          * 1) Add drawDirect(SkCanvas*) to VectorDrawableRoot
          * 2) fix VectorDrawable.cpp's Path::draw to not make a temporary path
          *    so that we don't break caching
          * 3) figure out how to set path's as volatile during animation
          * 4) if mRoot->getPaint() != null either promote to layer (during
          *    animation) or cache in SkSurface (for static content)
          *
          */
     }

 private:
    sp<VectorDrawableRoot> mRoot;
};

void SkiaRecordingCanvas::drawVectorDrawable(VectorDrawableRoot* tree) {
    drawDrawable(mDisplayList->allocateDrawable<VectorDrawable>(tree));
    mDisplayList->mVectorDrawables.push_back(tree);
}

// ----------------------------------------------------------------------------
// Recording Canvas draw operations: Bitmaps
// ----------------------------------------------------------------------------

inline static const SkPaint* nonAAPaint(const SkPaint* origPaint, SkPaint* tmpPaint) {
    if (origPaint && origPaint->isAntiAlias()) {
        *tmpPaint = *origPaint;
        tmpPaint->setAntiAlias(false);
        return tmpPaint;
    } else {
        return origPaint;
    }
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& bitmap, float left, float top, const SkPaint* paint) {
    SkBitmap skBitmap;
    bitmap.getSkBitmap(&skBitmap);

    sk_sp<SkImage> image = SkMakeImageFromRasterBitmap(skBitmap, kNever_SkCopyPixelsMode);
    if (!skBitmap.isImmutable()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
    SkPaint tmpPaint;
    mRecorder.drawImage(image, left, top, nonAAPaint(paint, &tmpPaint));
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& hwuiBitmap, const SkMatrix& matrix,
        const SkPaint* paint) {
    SkBitmap bitmap;
    hwuiBitmap.getSkBitmap(&bitmap);
    SkAutoCanvasRestore acr(&mRecorder, true);
    concat(matrix);
    sk_sp<SkImage> image = SkMakeImageFromRasterBitmap(bitmap, kNever_SkCopyPixelsMode);
    if (!bitmap.isImmutable()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
    SkPaint tmpPaint;
    mRecorder.drawImage(image, 0, 0, nonAAPaint(paint, &tmpPaint));
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& hwuiBitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop, float dstRight,
        float dstBottom, const SkPaint* paint) {
    SkBitmap bitmap;
    hwuiBitmap.getSkBitmap(&bitmap);
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    sk_sp<SkImage> image = SkMakeImageFromRasterBitmap(bitmap, kNever_SkCopyPixelsMode);
    if (!bitmap.isImmutable()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
    SkPaint tmpPaint;
    mRecorder.drawImageRect(image, srcRect, dstRect, nonAAPaint(paint, &tmpPaint));
}

void SkiaRecordingCanvas::drawNinePatch(Bitmap& hwuiBitmap, const Res_png_9patch& chunk,
        float dstLeft, float dstTop, float dstRight, float dstBottom, const SkPaint* paint) {
    SkBitmap bitmap;
    hwuiBitmap.getSkBitmap(&bitmap);

    SkCanvas::Lattice lattice;
    NinePatchUtils::SetLatticeDivs(&lattice, chunk, bitmap.width(), bitmap.height());

    lattice.fFlags = nullptr;
    int numFlags = 0;
    if (chunk.numColors > 0 && chunk.numColors == NinePatchUtils::NumDistinctRects(lattice)) {
        // We can expect the framework to give us a color for every distinct rect.
        // Skia requires placeholder flags for degenerate rects.
        numFlags = (lattice.fXCount + 1) * (lattice.fYCount + 1);
    }

    SkAutoSTMalloc<25, SkCanvas::Lattice::Flags> flags(numFlags);
    if (numFlags > 0) {
        NinePatchUtils::SetLatticeFlags(&lattice, flags.get(), numFlags, chunk);
    }

    lattice.fBounds = nullptr;
    SkRect dst = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    sk_sp<SkImage> image = SkMakeImageFromRasterBitmap(bitmap, kNever_SkCopyPixelsMode);
    if (!bitmap.isImmutable()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }

    SkPaint tmpPaint;
    mRecorder.drawImageLattice(image.get(), lattice, dst, nonAAPaint(paint, &tmpPaint));
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
