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
#pragma once

#include "HolePunch.h"
#include "RecordingCanvas.h"
#include "ReorderBarrierDrawables.h"
#include "SkiaCanvas.h"
#include "SkiaDisplayList.h"
#include "pipeline/skia/AnimatedDrawables.h"

class SkBitmap;
class SkMatrix;
class SkPaint;
class SkRRect;

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * A SkiaCanvas implementation that records drawing operations for deferred rendering backed by a
 * SkLiteRecorder and a SkiaDisplayList.
 */
class SkiaRecordingCanvas : public SkiaCanvas {
public:
    explicit SkiaRecordingCanvas(uirenderer::RenderNode* renderNode, int width, int height) {
        initDisplayList(renderNode, width, height);
    }

    virtual void setBitmap(const SkBitmap& bitmap) override {
        LOG_ALWAYS_FATAL("DisplayListCanvas is not backed by a bitmap.");
    }

    virtual void resetRecording(int width, int height,
                                uirenderer::RenderNode* renderNode = nullptr) override {
        initDisplayList(renderNode, width, height);
    }

    virtual void punchHole(const SkRRect& rect, float alpha) override;

    virtual void finishRecording(uirenderer::RenderNode* destination) override;
    std::unique_ptr<SkiaDisplayList> finishRecording();

    virtual void drawBitmap(Bitmap& bitmap, float left, float top, const Paint* paint) override;
    virtual void drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const Paint* paint) override;
    virtual void drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                            float srcBottom, float dstLeft, float dstTop, float dstRight,
                            float dstBottom, const Paint* paint) override;
    virtual void drawNinePatch(Bitmap& hwuiBitmap, const android::Res_png_9patch& chunk,
                               float dstLeft, float dstTop, float dstRight, float dstBottom,
                               const Paint* paint) override;
    virtual double drawAnimatedImage(AnimatedImageDrawable* animatedImage) override;

    virtual void drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
                               uirenderer::CanvasPropertyPrimitive* top,
                               uirenderer::CanvasPropertyPrimitive* right,
                               uirenderer::CanvasPropertyPrimitive* bottom,
                               uirenderer::CanvasPropertyPrimitive* rx,
                               uirenderer::CanvasPropertyPrimitive* ry,
                               uirenderer::CanvasPropertyPaint* paint) override;
    virtual void drawCircle(uirenderer::CanvasPropertyPrimitive* x,
                            uirenderer::CanvasPropertyPrimitive* y,
                            uirenderer::CanvasPropertyPrimitive* radius,
                            uirenderer::CanvasPropertyPaint* paint) override;
    virtual void drawRipple(const RippleDrawableParams& params) override;

    virtual void drawVectorDrawable(VectorDrawableRoot* vectorDrawable) override;

    virtual void enableZ(bool enableZ) override;
    virtual void drawMesh(const Mesh& mesh, sk_sp<SkBlender> blender, const Paint& paint) override;
    virtual void drawLayer(uirenderer::DeferredLayerUpdater* layerHandle) override;
    virtual void drawRenderNode(uirenderer::RenderNode* renderNode) override;

    void drawWebViewFunctor(int functor) override;

private:
    RecordingCanvas mRecorder;
    std::unique_ptr<SkiaDisplayList> mDisplayList;
    StartReorderBarrierDrawable* mCurrentBarrier;

    static void FilterForImage(SkPaint&);

    /**
     *  A new SkiaDisplayList is created or recycled if available.
     *
     *  @param renderNode is optional and used to recycle an old display list.
     *  @param width used to calculate recording bounds.
     *  @param height used to calculate recording bounds.
     */
    void initDisplayList(uirenderer::RenderNode* renderNode, int width, int height);

    void handleMutableImages(Bitmap& bitmap, DrawImagePayload& payload);

    void onFilterPaint(Paint& paint) override;

    using INHERITED = SkiaCanvas;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
