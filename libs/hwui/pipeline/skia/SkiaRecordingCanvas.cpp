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
    // Destructor of drawables created with allocateDrawable, will be invoked by ~LinearAllocator.
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
        // Create a ref-counted drawable, which is kept alive by sk_sp in SkLiteDL.
        sk_sp<SkDrawable> drawable(new LayerDrawable(layer));
        drawDrawable(drawable.get());
    }
}

void SkiaRecordingCanvas::drawRenderNode(uirenderer::RenderNode* renderNode) {
    // Record the child node. Drawable dtor will be invoked when mChildNodes deque is cleared.
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
    // Drawable dtor will be invoked when mChildFunctors deque is cleared.
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
         mRoot->draw(canvas);
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
    sk_sp<SkImage> image = bitmap.makeImage();
    SkPaint tmpPaint;
    mRecorder.drawImage(image, left, top, nonAAPaint(paint, &tmpPaint));
    // if image->unique() is true, then mRecorder.drawImage failed for some reason. It also means
    // it is not safe to store a raw SkImage pointer, because the image object will be destroyed
    // when this function ends.
    if (!bitmap.isImmutable() && image.get() && !image->unique()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& hwuiBitmap, const SkMatrix& matrix,
        const SkPaint* paint) {
    SkAutoCanvasRestore acr(&mRecorder, true);
    concat(matrix);
    sk_sp<SkImage> image = hwuiBitmap.makeImage();
    SkPaint tmpPaint;
    mRecorder.drawImage(image, 0, 0, nonAAPaint(paint, &tmpPaint));
    if (!hwuiBitmap.isImmutable() && image.get() && !image->unique()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

void SkiaRecordingCanvas::drawBitmap(Bitmap& hwuiBitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop, float dstRight,
        float dstBottom, const SkPaint* paint) {
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    sk_sp<SkImage> image = hwuiBitmap.makeImage();
    SkPaint tmpPaint;
    mRecorder.drawImageRect(image, srcRect, dstRect, nonAAPaint(paint, &tmpPaint));
    if (!hwuiBitmap.isImmutable() && image.get() && !image->unique() && !srcRect.isEmpty()
            && !dstRect.isEmpty()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

void SkiaRecordingCanvas::drawNinePatch(Bitmap& hwuiBitmap, const Res_png_9patch& chunk,
        float dstLeft, float dstTop, float dstRight, float dstBottom, const SkPaint* paint) {
    SkCanvas::Lattice lattice;
    NinePatchUtils::SetLatticeDivs(&lattice, chunk, hwuiBitmap.width(), hwuiBitmap.height());

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
    sk_sp<SkImage> image = hwuiBitmap.makeImage();

    SkPaint tmpPaint;
    mRecorder.drawImageLattice(image.get(), lattice, dst, nonAAPaint(paint, &tmpPaint));
    if (!hwuiBitmap.isImmutable() && image.get() && !image->unique() && !dst.isEmpty()) {
        mDisplayList->mMutableImages.push_back(image.get());
    }
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
