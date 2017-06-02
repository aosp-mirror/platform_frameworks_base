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

#include "SkiaPipeline.h"

#include "utils/TraceUtils.h"
#include <SkImageEncoder.h>
#include <SkImagePriv.h>
#include <SkOverdrawCanvas.h>
#include <SkOverdrawColorFilter.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include <SkPixelSerializer.h>
#include <SkStream.h>
#include "VectorDrawable.h"

#include <unistd.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

float   SkiaPipeline::mLightRadius = 0;
uint8_t SkiaPipeline::mAmbientShadowAlpha = 0;
uint8_t SkiaPipeline::mSpotShadowAlpha = 0;

Vector3 SkiaPipeline::mLightCenter = {FLT_MIN, FLT_MIN, FLT_MIN};

SkiaPipeline::SkiaPipeline(RenderThread& thread) :  mRenderThread(thread) {
    mVectorDrawables.reserve(30);
}

TaskManager* SkiaPipeline::getTaskManager() {
    return &mTaskManager;
}

void SkiaPipeline::onDestroyHardwareResources() {
    // No need to flush the caches here. There is a timer
    // which will flush temporary resources over time.
}

bool SkiaPipeline::pinImages(std::vector<SkImage*>& mutableImages) {
    for (SkImage* image : mutableImages) {
        if (SkImage_pinAsTexture(image, mRenderThread.getGrContext())) {
            mPinnedImages.emplace_back(sk_ref_sp(image));
        } else {
            return false;
        }
    }
    return true;
}

void SkiaPipeline::unpinImages() {
    for (auto& image : mPinnedImages) {
        SkImage_unpinAsTexture(image.get(), mRenderThread.getGrContext());
    }
    mPinnedImages.clear();
}

void SkiaPipeline::renderLayers(const FrameBuilder::LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue, bool opaque,
        const BakedOpRenderer::LightInfo& lightInfo) {
    updateLighting(lightGeometry, lightInfo);
    ATRACE_NAME("draw layers");
    renderVectorDrawableCache();
    renderLayersImpl(*layerUpdateQueue, opaque);
    layerUpdateQueue->clear();
}

void SkiaPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) {
    // Render all layers that need to be updated, in order.
    for (size_t i = 0; i < layers.entries().size(); i++) {
        RenderNode* layerNode = layers.entries()[i].renderNode.get();
        // only schedule repaint if node still on layer - possible it may have been
        // removed during a dropped frame, but layers may still remain scheduled so
        // as not to lose info on what portion is damaged
        if (CC_LIKELY(layerNode->getLayerSurface() != nullptr)) {
            SkASSERT(layerNode->getLayerSurface());
            SkASSERT(layerNode->getDisplayList()->isSkiaDL());
            SkiaDisplayList* displayList = (SkiaDisplayList*)layerNode->getDisplayList();
            if (!displayList || displayList->isEmpty()) {
                SkDEBUGF(("%p drawLayers(%s) : missing drawable", this, layerNode->getName()));
                return;
            }

            const Rect& layerDamage = layers.entries()[i].damage;

            SkCanvas* layerCanvas = layerNode->getLayerSurface()->getCanvas();

            int saveCount = layerCanvas->save();
            SkASSERT(saveCount == 1);

            layerCanvas->androidFramework_setDeviceClipRestriction(layerDamage.toSkIRect());

            auto savedLightCenter = mLightCenter;
            // map current light center into RenderNode's coordinate space
            layerNode->getSkiaLayer()->inverseTransformInWindow.mapPoint3d(mLightCenter);

            const RenderProperties& properties = layerNode->properties();
            const SkRect bounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());
            if (properties.getClipToBounds() && layerCanvas->quickReject(bounds)) {
                return;
            }

            layerNode->getSkiaLayer()->hasRenderedSinceRepaint = false;
            layerCanvas->clear(SK_ColorTRANSPARENT);

            RenderNodeDrawable root(layerNode, layerCanvas, false);
            root.forceDraw(layerCanvas);
            layerCanvas->restoreToCount(saveCount);
            layerCanvas->flush();
            mLightCenter = savedLightCenter;
        }
    }
}

bool SkiaPipeline::createOrUpdateLayer(RenderNode* node,
        const DamageAccumulator& damageAccumulator) {
    SkSurface* layer = node->getLayerSurface();
    if (!layer || layer->width() != node->getWidth() || layer->height() != node->getHeight()) {
        SkImageInfo info = SkImageInfo::MakeN32Premul(node->getWidth(), node->getHeight());
        SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
        SkASSERT(mRenderThread.getGrContext() != nullptr);
        node->setLayerSurface(
                SkSurface::MakeRenderTarget(mRenderThread.getGrContext(), SkBudgeted::kYes,
                        info, 0, &props));
        if (node->getLayerSurface()) {
            // update the transform in window of the layer to reset its origin wrt light source
            // position
            Matrix4 windowTransform;
            damageAccumulator.computeCurrentTransform(&windowTransform);
            node->getSkiaLayer()->inverseTransformInWindow = windowTransform;
        }
        return true;
    }
    return false;
}

void SkiaPipeline::destroyLayer(RenderNode* node) {
    node->setLayerSurface(nullptr);
}

void SkiaPipeline::prepareToDraw(const RenderThread& thread, Bitmap* bitmap) {
    GrContext* context = thread.getGrContext();
    if (context) {
        ATRACE_FORMAT("Bitmap#prepareToDraw %dx%d", bitmap->width(), bitmap->height());
        auto image = bitmap->makeImage();
        if (image.get() && !bitmap->isHardware()) {
            SkImage_pinAsTexture(image.get(), context);
            SkImage_unpinAsTexture(image.get(), context);
        }
    }
}

// Encodes to PNG, unless there is already encoded data, in which case that gets
// used.
class PngPixelSerializer : public SkPixelSerializer {
public:
    bool onUseEncodedData(const void*, size_t) override { return true; }
    SkData* onEncode(const SkPixmap& pixmap) override {
        SkDynamicMemoryWStream buf;
        return SkEncodeImage(&buf, pixmap, SkEncodedImageFormat::kPNG, 100)
               ? buf.detachAsData().release()
               : nullptr;
    }
};

void SkiaPipeline::renderVectorDrawableCache() {
    //render VectorDrawables into offscreen buffers
    for (auto vd : mVectorDrawables) {
        sk_sp<SkSurface> surface;
        if (!vd->canReuseSurface()) {
#ifndef ANDROID_ENABLE_LINEAR_BLENDING
            sk_sp<SkColorSpace> colorSpace = nullptr;
#else
            sk_sp<SkColorSpace> colorSpace = SkColorSpace::MakeSRGB();
#endif
            int scaledWidth = SkScalarCeilToInt(vd->properties().getScaledWidth());
            int scaledHeight = SkScalarCeilToInt(vd->properties().getScaledHeight());
            SkImageInfo info = SkImageInfo::MakeN32(scaledWidth, scaledHeight,
                    kPremul_SkAlphaType, colorSpace);
            SkASSERT(mRenderThread.getGrContext() != nullptr);
            surface = SkSurface::MakeRenderTarget(mRenderThread.getGrContext(), SkBudgeted::kYes,
                    info);
        }
        vd->updateCache(surface);
    }
    mVectorDrawables.clear();
}

void SkiaPipeline::renderFrame(const LayerUpdateQueue& layers, const SkRect& clip,
        const std::vector<sp<RenderNode>>& nodes, bool opaque, const Rect &contentDrawBounds,
        sk_sp<SkSurface> surface) {

    renderVectorDrawableCache();

    // draw all layers up front
    renderLayersImpl(layers, opaque);

    // initialize the canvas for the current frame
    SkCanvas* canvas = surface->getCanvas();

    std::unique_ptr<SkPictureRecorder> recorder;
    bool recordingPicture = false;
    char prop[PROPERTY_VALUE_MAX];
    if (skpCaptureEnabled()) {
        property_get("debug.hwui.capture_frame_as_skp", prop, "0");
        recordingPicture = prop[0] != '0' && access(prop, F_OK) != 0;
        if (recordingPicture) {
            recorder.reset(new SkPictureRecorder());
            canvas = recorder->beginRecording(surface->width(), surface->height(),
                    nullptr, SkPictureRecorder::kPlaybackDrawPicture_RecordFlag);
        }
    }

    renderFrameImpl(layers, clip, nodes, opaque, contentDrawBounds, canvas);

    if (skpCaptureEnabled() && recordingPicture) {
        sk_sp<SkPicture> picture = recorder->finishRecordingAsPicture();
        if (picture->approximateOpCount() > 0) {
            SkFILEWStream stream(prop);
            if (stream.isValid()) {
                PngPixelSerializer serializer;
                picture->serialize(&stream, &serializer);
                stream.flush();
                SkDebugf("Captured Drawing Output (%d bytes) for frame. %s", stream.bytesWritten(), prop);
            }
        }
        surface->getCanvas()->drawPicture(picture);
    }

    if (CC_UNLIKELY(Properties::debugOverdraw)) {
        renderOverdraw(layers, clip, nodes, contentDrawBounds, surface);
    }

    ATRACE_NAME("flush commands");
    canvas->flush();
}

namespace {
static Rect nodeBounds(RenderNode& node) {
    auto& props = node.properties();
    return Rect(props.getLeft(), props.getTop(),
            props.getRight(), props.getBottom());
}
}

void SkiaPipeline::renderFrameImpl(const LayerUpdateQueue& layers, const SkRect& clip,
        const std::vector<sp<RenderNode>>& nodes, bool opaque, const Rect &contentDrawBounds,
        SkCanvas* canvas) {
    SkAutoCanvasRestore saver(canvas, true);
    canvas->androidFramework_setDeviceClipRestriction(clip.roundOut());

    if (!opaque) {
        canvas->clear(SK_ColorTRANSPARENT);
    }

    if (1 == nodes.size()) {
        if (!nodes[0]->nothingToDraw()) {
            RenderNodeDrawable root(nodes[0].get(), canvas);
            root.draw(canvas);
        }
    } else if (0 == nodes.size()) {
        //nothing to draw
    } else {
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
            RenderNodeDrawable backdropNode(nodes[0].get(), canvas);
            if (content.right < backdrop.right) {
                // draw backdrop to right side of content
                SkAutoCanvasRestore acr(canvas, true);
                canvas->clipRect(SkRect::MakeLTRB(content.right, backdrop.top,
                        backdrop.right, backdrop.bottom));
                backdropNode.draw(canvas);
            }
            if (content.bottom < backdrop.bottom) {
                // draw backdrop to bottom of content
                // Note: bottom fill uses content left/right, to avoid overdrawing left/right fill
                SkAutoCanvasRestore acr(canvas, true);
                canvas->clipRect(SkRect::MakeLTRB(content.left, content.bottom,
                        content.right, backdrop.bottom));
                backdropNode.draw(canvas);
            }
        }

        RenderNodeDrawable contentNode(nodes[1].get(), canvas);
        if (!backdrop.isEmpty()) {
            // content node translation to catch up with backdrop
            float dx = backdrop.left - contentDrawBounds.left;
            float dy = backdrop.top - contentDrawBounds.top;

            SkAutoCanvasRestore acr(canvas, true);
            canvas->translate(dx, dy);
            const SkRect contentLocalClip = SkRect::MakeXYWH(contentDrawBounds.left,
                    contentDrawBounds.top, backdrop.getWidth(), backdrop.getHeight());
            canvas->clipRect(contentLocalClip);
            contentNode.draw(canvas);
        } else {
            SkAutoCanvasRestore acr(canvas, true);
            contentNode.draw(canvas);
        }

        // remaining overlay nodes, simply defer
        for (size_t index = 2; index < nodes.size(); index++) {
            if (!nodes[index]->nothingToDraw()) {
                SkAutoCanvasRestore acr(canvas, true);
                RenderNodeDrawable overlayNode(nodes[index].get(), canvas);
                overlayNode.draw(canvas);
            }
        }
    }
}

void SkiaPipeline::dumpResourceCacheUsage() const {
    int resources, maxResources;
    size_t bytes, maxBytes;
    mRenderThread.getGrContext()->getResourceCacheUsage(&resources, &bytes);
    mRenderThread.getGrContext()->getResourceCacheLimits(&maxResources, &maxBytes);

    SkString log("Resource Cache Usage:\n");
    log.appendf("%8d items out of %d maximum items\n", resources, maxResources);
    log.appendf("%8zu bytes (%.2f MB) out of %.2f MB maximum\n",
            bytes, bytes * (1.0f / (1024.0f * 1024.0f)), maxBytes * (1.0f / (1024.0f * 1024.0f)));

    ALOGD("%s", log.c_str());
}

// Overdraw debugging

// These colors should be kept in sync with Caches::getOverdrawColor() with a few differences.
// This implementation:
// (1) Requires transparent entries for "no overdraw" and "single draws".
// (2) Requires premul colors (instead of unpremul).
// (3) Requires RGBA colors (instead of BGRA).
static const uint32_t kOverdrawColors[2][6] = {
        { 0x00000000, 0x00000000, 0x2f2f0000, 0x2f002f00, 0x3f00003f, 0x7f00007f, },
        { 0x00000000, 0x00000000, 0x2f2f0000, 0x4f004f4f, 0x5f50335f, 0x7f00007f, },
};

void SkiaPipeline::renderOverdraw(const LayerUpdateQueue& layers, const SkRect& clip,
        const std::vector<sp<RenderNode>>& nodes, const Rect &contentDrawBounds,
        sk_sp<SkSurface> surface) {
    // Set up the overdraw canvas.
    SkImageInfo offscreenInfo = SkImageInfo::MakeA8(surface->width(), surface->height());
    sk_sp<SkSurface> offscreen = surface->makeSurface(offscreenInfo);
    SkOverdrawCanvas overdrawCanvas(offscreen->getCanvas());

    // Fake a redraw to replay the draw commands.  This will increment the alpha channel
    // each time a pixel would have been drawn.
    // Pass true for opaque so we skip the clear - the overdrawCanvas is already zero
    // initialized.
    renderFrameImpl(layers, clip, nodes, true, contentDrawBounds, &overdrawCanvas);
    sk_sp<SkImage> counts = offscreen->makeImageSnapshot();

    // Draw overdraw colors to the canvas.  The color filter will convert counts to colors.
    SkPaint paint;
    const SkPMColor* colors = kOverdrawColors[static_cast<int>(Properties::overdrawColorSet)];
    paint.setColorFilter(SkOverdrawColorFilter::Make(colors));
    surface->getCanvas()->drawImage(counts.get(), 0.0f, 0.0f, &paint);
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
