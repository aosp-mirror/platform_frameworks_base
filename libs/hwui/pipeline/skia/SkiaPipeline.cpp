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
#include <SkOSFile.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include <SkPixelSerializer.h>
#include <SkStream.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

float   SkiaPipeline::mLightRadius = 0;
uint8_t SkiaPipeline::mAmbientShadowAlpha = 0;
uint8_t SkiaPipeline::mSpotShadowAlpha = 0;

Vector3 SkiaPipeline::mLightCenter = {FLT_MIN, FLT_MIN, FLT_MIN};

SkiaPipeline::SkiaPipeline(RenderThread& thread) :  mRenderThread(thread) { }

TaskManager* SkiaPipeline::getTaskManager() {
    return &mTaskManager;
}

void SkiaPipeline::onDestroyHardwareResources() {
    // No need to flush the caches here. There is a timer
    // which will flush temporary resources over time.
}

void SkiaPipeline::renderLayers(const FrameBuilder::LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue, bool opaque,
        const BakedOpRenderer::LightInfo& lightInfo) {
    updateLighting(lightGeometry, lightInfo);
    ATRACE_NAME("draw layers");
    renderLayersImpl(*layerUpdateQueue, opaque);
    layerUpdateQueue->clear();
}

void SkiaPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) {
    // Render all layers that need to be updated, in order.
    for (size_t i = 0; i < layers.entries().size(); i++) {
        RenderNode* layerNode = layers.entries()[i].renderNode;
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

            layerCanvas->clipRect(layerDamage.toSkRect(), SkRegion::kReplace_Op);

            auto savedLightCenter = mLightCenter;
            // map current light center into RenderNode's coordinate space
            layerNode->getSkiaLayer()->inverseTransformInWindow.mapPoint3d(mLightCenter);

            const RenderProperties& properties = layerNode->properties();
            const SkRect bounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());
            if (properties.getClipToBounds() && layerCanvas->quickReject(bounds)) {
                return;
            }

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
        SkBitmap skiaBitmap;
        bitmap->getSkBitmap(&skiaBitmap);
        sk_sp<SkImage> image = SkMakeImageFromRasterBitmap(skiaBitmap, kNever_SkCopyPixelsMode);
        SkImage_pinAsTexture(image.get(), context);
        SkImage_unpinAsTexture(image.get(), context);
    }
}

// Encodes to PNG, unless there is already encoded data, in which case that gets
// used.
class PngPixelSerializer : public SkPixelSerializer {
public:
    bool onUseEncodedData(const void*, size_t) override { return true; }
    SkData* onEncode(const SkPixmap& pixmap) override {
        return SkImageEncoder::EncodeData(pixmap.info(), pixmap.addr(), pixmap.rowBytes(),
                                          SkImageEncoder::kPNG_Type, 100);
    }
};

void SkiaPipeline::renderFrame(const LayerUpdateQueue& layers, const SkRect& clip,
        const std::vector<sp<RenderNode>>& nodes, bool opaque, const Rect &contentDrawBounds,
        sk_sp<SkSurface> surface) {

    // unpin all mutable images that were attached to nodes deleted while on the UI thread
    SkiaDisplayList::cleanupImages(surface->getCanvas()->getGrContext());

    // draw all layers up front
    renderLayersImpl(layers, opaque);

    // initialize the canvas for the current frame
    SkCanvas* canvas = surface->getCanvas();

    std::unique_ptr<SkPictureRecorder> recorder;
    bool recordingPicture = false;
    char prop[PROPERTY_VALUE_MAX];
    if (skpCaptureEnabled()) {
        property_get("debug.hwui.capture_frame_as_skp", prop, "0");
        recordingPicture = prop[0] != '0' && !sk_exists(prop);
        if (recordingPicture) {
            recorder.reset(new SkPictureRecorder());
            canvas = recorder->beginRecording(surface->width(), surface->height(),
                    nullptr, SkPictureRecorder::kPlaybackDrawPicture_RecordFlag);
        }
    }

    canvas->clipRect(clip, SkRegion::kReplace_Op);

    if (!opaque) {
        canvas->clear(SK_ColorTRANSPARENT);
    }

    // If there are multiple render nodes, they are laid out as follows:
    // #0 - backdrop (content + caption)
    // #1 - content (positioned at (0,0) and clipped to - its bounds mContentDrawBounds)
    // #2 - additional overlay nodes
    // Usually the backdrop cannot be seen since it will be entirely covered by the content. While
    // resizing however it might become partially visible. The following render loop will crop the
    // backdrop against the content and draw the remaining part of it. It will then draw the content
    // cropped to the backdrop (since that indicates a shrinking of the window).
    //
    // Additional nodes will be drawn on top with no particular clipping semantics.

    // The bounds of the backdrop against which the content should be clipped.
    Rect backdropBounds = contentDrawBounds;
    // Usually the contents bounds should be mContentDrawBounds - however - we will
    // move it towards the fixed edge to give it a more stable appearance (for the moment).
    // If there is no content bounds we ignore the layering as stated above and start with 2.
    int layer = (contentDrawBounds.isEmpty() || nodes.size() == 1) ? 2 : 0;

    for (const sp<RenderNode>& node : nodes) {
        if (node->nothingToDraw()) continue;

        SkASSERT(node->getDisplayList()->isSkiaDL());

        int count = canvas->save();

        if (layer == 0) {
            const RenderProperties& properties = node->properties();
            Rect targetBounds(properties.getLeft(), properties.getTop(),
                              properties.getRight(), properties.getBottom());
            // Move the content bounds towards the fixed corner of the backdrop.
            const int x = targetBounds.left;
            const int y = targetBounds.top;
            // Remember the intersection of the target bounds and the intersection bounds against
            // which we have to crop the content.
            backdropBounds.set(x, y, x + backdropBounds.getWidth(), y + backdropBounds.getHeight());
            backdropBounds.doIntersect(targetBounds);
        } else if (layer == 1) {
            // We shift and clip the content to match its final location in the window.
            const SkRect clip = SkRect::MakeXYWH(contentDrawBounds.left, contentDrawBounds.top,
                                                 backdropBounds.getWidth(), backdropBounds.getHeight());
            const float dx = backdropBounds.left - contentDrawBounds.left;
            const float dy = backdropBounds.top - contentDrawBounds.top;
            canvas->translate(dx, dy);
            // It gets cropped against the bounds of the backdrop to stay inside.
            canvas->clipRect(clip, SkRegion::kIntersect_Op);
        }

        RenderNodeDrawable root(node.get(), canvas);
        root.draw(canvas);
        canvas->restoreToCount(count);
        layer++;
    }

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

    ATRACE_NAME("flush commands");
    canvas->flush();
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

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
